package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android half of the v32 warm-Hermes transport (the pure wire/policy
 * core is [HermesWarmTransport.kt]): owns the persistent in-guest API
 * server process and the streaming chat client that talks to it.
 *
 * The server is Hermes 0.19.0's own gateway (`hermes gateway run
 * --replace`) started inside the same PRoot guest the CLI turns use,
 * with only the api_server platform enabled (`API_SERVER_ENABLED=1`,
 * loopback bind, the app-generated [HermesWarmServerKey]). PRoot shares
 * the phone's loopback, so the app reaches it at [HermesWarmEndpoint]
 * directly — the same way the app's Preview reaches guest dev servers.
 * It stays alive across turns (Python, the Hermes import graph, config
 * and tool registries are paid once, not per message), answers turns
 * over SSE, and is stopped after [HermesWarmTiming.IDLE_STOP_MS] idle
 * or when its [HermesWarmFingerprint] changes (workspace, provider,
 * endpoint or key — the values its process environment carries).
 *
 * Every failure mode degrades to the CLI transport: [ensure] reports
 * false, the chat client reports [HermesWarmTurnOutcome.Unavailable] or
 * a zero-delivery failure, and [HermesWarmFallback] hands the turn to
 * the one-shot path. The server is an optimisation, never a dependency.
 */

/** Everything needed to spawn one warm server process. */
internal data class HermesWarmLaunch(
    val fingerprint: HermesWarmFingerprint,
    val proot: File,
    val rootfs: File,
    val workspace: File,
    val guestWorkspacePath: String,
    val environment: Map<String, String>,
)

internal class HermesWarmServerManager(private val context: Context) {
    private val installer = RuntimeInstaller(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var serverProcess: Process? = null
    @Volatile private var serverFingerprint: HermesWarmFingerprint? = null
    @Volatile private var lastFailureAtMs: Long = 0L
    @Volatile private var lastUsedAtMs: Long = 0L
    private var idleStopJob: Job? = null

    private val warmDir: File get() = File(context.filesDir, "hermes-warm").apply { mkdirs() }

    /**
     * The local API server's bearer key, generated on first use and
     * kept in app-private storage across launches (an orphaned server
     * from a killed app process answers to the same key, so it can be
     * health-checked — and replaced — on the next launch).
     */
    fun serverKey(): String {
        val file = File(warmDir, "api-key.txt")
        val existing = runCatching { file.readText().trim() }.getOrNull().orEmpty()
        if (HermesWarmServerKey.isUsable(existing)) return existing
        val generated = HermesWarmServerKey.generate()
        runCatching { file.writeText(generated) }
        return generated
    }

    /**
     * Makes sure a healthy server matching [launch]'s fingerprint is
     * answering, spawning (or replacing) the process when needed.
     * Returns false — quickly, while a failure cooldown is running —
     * when the server cannot be brought up; the caller then runs the
     * turn on the CLI path and a later turn retries.
     */
    suspend fun ensure(launch: HermesWarmLaunch): Boolean {
        val now = System.currentTimeMillis()
        val current = serverProcess
        if (serverFingerprint == launch.fingerprint && current?.isAlive == true && healthCheck()) {
            markUsed()
            return true
        }
        if (serverFingerprint == launch.fingerprint &&
            HermesWarmTiming.inCooldown(lastFailureAtMs, now)
        ) {
            return false
        }
        // Whatever is running — ours with a stale fingerprint, or an
        // orphan from a killed app process — is replaced: the spawn
        // carries --replace, and the health wait below only accepts a
        // listener that stays up continuously, so a dying predecessor's
        // last answers cannot be mistaken for the new server.
        stopServer()
        val process = runCatching {
            installer.process(
                proot = launch.proot,
                rootfs = launch.rootfs,
                workspace = launch.workspace,
                environment = launch.environment,
                guestCommand = listOf(
                    RuntimeInstaller.HERMES_GUEST_PATH,
                    "gateway", "run", "--replace",
                ),
                guestWorkspacePath = launch.guestWorkspacePath,
                // Hermes saves its session database through atomic
                // renames; PRoot's hard-link emulation would dangle them
                // (the same reason the CLI turns spawn this way).
                emulateHardLinks = false,
                outputFile = File(warmDir, "gateway.log"),
            )
        }.getOrElse { error ->
            Log.w(TAG, "Warm Hermes server spawn failed", error)
            null
        }
        if (process == null) {
            lastFailureAtMs = System.currentTimeMillis()
            return false
        }
        serverProcess = process
        serverFingerprint = launch.fingerprint
        val deadline = System.currentTimeMillis() + HermesWarmTiming.START_WAIT_BUDGET_MS
        var healthySinceMs = 0L
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) break
            if (healthCheck()) {
                if (healthySinceMs == 0L) healthySinceMs = System.currentTimeMillis()
                // Two continuous seconds of health: a replaced
                // predecessor answers only until it dies, so a listener
                // that survives this window is the new server.
                if (System.currentTimeMillis() - healthySinceMs >= HEALTH_STABLE_MS) {
                    markUsed()
                    return true
                }
            } else {
                healthySinceMs = 0L
            }
            delay(HermesWarmTiming.HEALTH_POLL_INTERVAL_MS)
        }
        Log.w(TAG, "Warm Hermes server did not become healthy in time")
        lastFailureAtMs = System.currentTimeMillis()
        stopServer()
        return false
    }

    /** Whether the answering server is one that accepts our key (any 200 on the authed models route). */
    fun healthCheck(): Boolean = httpGet(HermesWarmEndpoint.MODELS_URL, serverKey(), HEALTH_TIMEOUT_MS) == HttpURLConnection.HTTP_OK

    /** Probes the server's own session store for [sessionId] (see [HermesSessionPlanner]). */
    fun probeSession(sessionId: String): HermesSessionProbe {
        if (sessionId.isBlank()) return HermesSessionProbe.MISSING
        return when (httpGet(HermesWarmEndpoint.sessionUrl(sessionId), serverKey(), HEALTH_TIMEOUT_MS)) {
            HttpURLConnection.HTTP_OK -> HermesSessionProbe.EXISTS
            HttpURLConnection.HTTP_NOT_FOUND -> HermesSessionProbe.MISSING
            else -> HermesSessionProbe.UNKNOWN
        }
    }

    fun markUsed() {
        lastUsedAtMs = System.currentTimeMillis()
        scheduleIdleStop()
    }

    private fun scheduleIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = scope.launch {
            delay(HermesWarmTiming.IDLE_STOP_MS)
            if (HermesWarmTiming.idleExpired(lastUsedAtMs, System.currentTimeMillis())) {
                Log.d(TAG, "Stopping idle warm Hermes server")
                stopServer()
            }
        }
    }

    fun stopServer() {
        val process = serverProcess
        serverProcess = null
        serverFingerprint = null
        if (process != null && process.isAlive) {
            process.destroy()
            scope.launch {
                delay(STOP_GRACE_MS)
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    /** Status code of an authed GET, or -1 when the request never got a response. */
    private fun httpGet(url: String, apiKey: String, timeoutMs: Int): Int = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(-1)

    companion object {
        private const val TAG = "HermesWarmServer"
        private const val HEALTH_TIMEOUT_MS = 3_000
        private const val HEALTH_STABLE_MS = 500L
        private const val STOP_GRACE_MS = 200L
    }
}

/**
 * One streaming chat turn against the warm server. Single attempt, no
 * retries: the bridge owns the fallback decision ([HermesWarmFallback])
 * and re-running a turn is a transport change, not a retry.
 */
internal class HermesWarmChatClient {
    @Volatile private var activeConnection: HttpURLConnection? = null

    /** Severs the in-flight request, if any; called from the bridge's stop path. */
    fun disconnect() {
        runCatching { activeConnection?.disconnect() }
    }

    suspend fun streamTurn(
        apiKey: String,
        model: String,
        prompt: String,
        plan: HermesSessionPlan,
        isStopped: () -> Boolean,
        onTextDelta: suspend (String) -> Unit,
        onToolActivity: suspend (String) -> Unit,
    ): HermesWarmTurnOutcome {
        val connection = (URL(HermesWarmEndpoint.CHAT_COMPLETIONS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            if (plan is HermesSessionPlan.Resume) {
                setRequestProperty("X-Hermes-Session-Id", plan.sessionId)
            }
        }
        activeConnection = connection
        try {
            val bodyJson = HermesLocalRequests.chatCompletionBody(model, prompt).toString()
            val code = try {
                connection.outputStream.use { stream -> stream.write(bodyJson.toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } catch (error: Exception) {
                // No HTTP response ever arrived: the turn provably never
                // ran on the server, so the CLI path can take it over.
                if (isStopped()) return HermesWarmTurnOutcome.Stopped
                Log.w(TAG, "Warm Hermes turn never reached the server", error)
                return HermesWarmTurnOutcome.Unavailable
            }
            val responseSessionId = connection.getHeaderField("X-Hermes-Session-Id")
                ?.takeIf(String::isNotBlank)
                ?: (plan as? HermesSessionPlan.Resume)?.sessionId
            if (code != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                return HermesWarmTurnOutcome.Failed(
                    errorText = HermesLocalErrors.parseErrorMessage(errorBody)
                        ?: "Hermes returned HTTP status $code.",
                    sessionId = responseSessionId,
                    deliveredText = false,
                )
            }
            val parser = HermesLocalSseParser()
            val assembler = HermesStreamAssembler()
            var deliveredText = false
            var finish: HermesLocalEvent.Finished? = null
            var sawStreamEnd = false
            var readFailed = false

            suspend fun handle(events: List<HermesLocalEvent>) {
                for (event in events) {
                    when (event) {
                        is HermesLocalEvent.TextDelta -> {
                            val visible = assembler.feed(event.text)
                            if (visible.isNotEmpty()) {
                                deliveredText = true
                                onTextDelta(visible)
                            }
                        }
                        is HermesLocalEvent.ToolProgress -> {
                            if (event.status == "running") {
                                onToolActivity(event.label.ifBlank { event.tool })
                            }
                        }
                        is HermesLocalEvent.Finished -> finish = event
                        HermesLocalEvent.StreamEnded -> sawStreamEnd = true
                        // Reasoning is dropped, exactly like the CLI
                        // path's classifier drops it; status frames and
                        // approval requests (impossible under the app's
                        // approvals-off config) carry nothing the chat
                        // shows.
                        is HermesLocalEvent.ReasoningDelta,
                        is HermesLocalEvent.Status,
                        HermesLocalEvent.ApprovalRequested,
                        -> Unit
                    }
                }
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            try {
                while (!sawStreamEnd) {
                    if (isStopped()) return HermesWarmTurnOutcome.Stopped
                    val line = reader.readLine() ?: break
                    handle(parser.feed(line))
                }
            } catch (error: Exception) {
                if (isStopped()) return HermesWarmTurnOutcome.Stopped
                Log.w(TAG, "Warm Hermes stream dropped mid-turn", error)
                readFailed = true
            }
            if (!readFailed) handle(parser.endOfStream())
            val tail = assembler.finish()
            if (tail.isNotEmpty()) {
                deliveredText = true
                onTextDelta(tail)
            }
            if (isStopped()) return HermesWarmTurnOutcome.Stopped
            return when (val verdict = HermesWarmStreamEnd.verdict(
                finishReason = finish?.finishReason,
                finishError = finish?.errorMessage,
                sawStreamEnd = sawStreamEnd,
                readFailed = readFailed,
            )) {
                HermesWarmStreamEnd.Verdict.Completed -> HermesWarmTurnOutcome.Completed(
                    sessionId = responseSessionId,
                    // The stored message is exactly what the user saw
                    // stream in; unless the assembler diverged (its
                    // prefix invariant failed — pinned impossible by
                    // tests on realistic streams) that is also the
                    // canonical classified text.
                    text = if (assembler.diverged) assembler.emittedText else assembler.outcome.responseText,
                )
                is HermesWarmStreamEnd.Verdict.Failed -> HermesWarmTurnOutcome.Failed(
                    errorText = verdict.message,
                    sessionId = responseSessionId,
                    deliveredText = deliveredText,
                )
                HermesWarmStreamEnd.Verdict.Dropped -> HermesWarmTurnOutcome.Failed(
                    errorText = "The connection to Hermes dropped before the reply finished.",
                    sessionId = responseSessionId,
                    deliveredText = deliveredText,
                )
            }
        } finally {
            if (activeConnection === connection) activeConnection = null
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        private const val TAG = "HermesWarmClient"
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** Per-read gap tolerance: a server-side tool can run silently for minutes. */
        private const val READ_TIMEOUT_MS = 120_000
    }
}
