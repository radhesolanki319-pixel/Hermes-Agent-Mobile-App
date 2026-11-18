package com.jarves.mh.runtime

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for a remote Hermes gateway (Phase 4): the app talks to a Hermes
 * Agent running on the user's own always-on host instead of the embedded
 * PRoot copy. The wire contract is Hermes's own OpenAI-compatible API
 * server, read from the Hermes source (gateway/platforms/api_server.py and
 * api_server_openai_routes.py), not guessed:
 *
 *  - `POST /v1/chat/completions` with an OpenAI chat body; `stream: true`
 *    answers with `chat.completion.chunk` SSE frames (`data: <json>` lines,
 *    terminated by `data: [DONE]`), plus named frames `hermes.tool.progress`
 *    ({tool, label, toolCallId, status}), `hermes.status` ({kind, text}) and
 *    `approval.request`.
 *  - Every route except `/health` requires `Authorization: Bearer
 *    <API_SERVER_KEY>`; a bad key gets a 401 OpenAI-style error envelope.
 *  - Conversation continuity is the `X-Hermes-Session-Id` header: the
 *    gateway loads that session's history from its own state store (the
 *    request body only needs the new user message) and echoes the id back
 *    in the response headers. Client-chosen ids are accepted — the session
 *    endpoints take caller-provided ids — as long as they are path-safe.
 *  - A `system` message would become an ephemeral system prompt layered
 *    over the host's own; this client sends a single `user` message (the
 *    runtime prompt, which already carries the GenUI capability block
 *    MainViewModel appends for every bridge).
 *  - Retries of one turn carry a stable `Idempotency-Key`: the gateway
 *    deduplicates on (key, body fingerprint), so a cold-start retry can
 *    never run the same turn twice.
 */

/** Where Hermes runs for a chat turn: on this phone (embedded) or on a remote gateway host. */
data class HermesHostConfig(
    val remote: Boolean,
    val baseUrl: String,
    val apiKey: String,
)

/** Vault pool id the gateway key is stored under; [com.jarves.mh.data.ApiKeyVault] pools by arbitrary id. */
internal const val HERMES_GATEWAY_VAULT_ID = "HERMES_GATEWAY"

/**
 * User-facing failure reasons for the remote path. Bridges report English
 * reasons and MainViewModel localizes the known ones for display. The
 * wording deliberately avoids the substrings MainViewModel's provider-key
 * rotation keys on ("api key", "authentication", "http 401", …): a remote
 * host failure must never trigger a retry with a different *provider* key,
 * because the provider key lives on the host, not in the app.
 */
internal object HermesRemoteMessages {
    // Since v27 there is no Hermes host card in the app (the gateway is
    // pre-connected from inside the build), so none of these messages may
    // send the user to a settings surface that no longer exists.
    const val KEY_REJECTED = "The Hermes host rejected the gateway key."
    const val NO_URL = "No Hermes host URL is configured."
    const val NO_KEY = "No Hermes host gateway key is configured."
    const val UNREACHABLE = "Could not reach Hermes on its computer. It may be waking up — wait a few seconds and try again."

    /**
     * v28 diagnostics: [UNREACHABLE] plus the host the app was actually
     * pointing at and the root network cause (exception class + message),
     * so a screenshot of the failure shows both — a bare "unreachable"
     * hides whether the address, the DNS, or the connection is at fault.
     * MainViewModel's exact-match localization deliberately falls through
     * to this raw text: the detail IS the message.
     */
    fun unreachableDetail(host: String, error: Exception?): String {
        val cause = error?.let { e ->
            " (${e.javaClass.simpleName}: ${e.message?.takeIf(String::isNotBlank) ?: "no detail"})"
        }.orEmpty()
        return "$UNREACHABLE Host: $host.$cause"
    }
    const val STILL_WAKING = "The Hermes host is still waking up. Free hosts sleep when idle — wait a minute, then send again."
    const val DROPPED = "The connection to the Hermes host dropped before the reply finished."
    const val PROBE_OK = "Hermes host is awake and the gateway key works."

    fun httpStatus(code: Int): String = "The Hermes host returned HTTP status $code. Try again in a moment."
}

internal object HermesGatewayUrls {
    /**
     * Canonical gateway base URL: scheme added when missing, trailing
     * slashes and a trailing `/v1` removed (endpoint builders add `/v1`
     * themselves, so a pasted `https://host/v1` must not double it).
     */
    fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.contains("://")) value = "https://$value"
        value = value.trimEnd('/')
        if (value.endsWith("/v1")) value = value.removeSuffix("/v1").trimEnd('/')
        return value
    }

    /** Host of a normalized base URL (lowercased, no port), or "" when it cannot be parsed. */
    fun hostOf(normalizedBaseUrl: String): String =
        runCatching { URL(normalizedBaseUrl).host }.getOrNull()?.lowercase().orEmpty()

    fun chatCompletionsUrl(normalizedBaseUrl: String): String = "$normalizedBaseUrl/v1/chat/completions"
    fun modelsUrl(normalizedBaseUrl: String): String = "$normalizedBaseUrl/v1/models"
    fun healthUrl(normalizedBaseUrl: String): String = "$normalizedBaseUrl/health"
}

/**
 * v26: the baked-in gateway pre-connect. Hermes's own always-on computer
 * (the gateway on Render) is connected from inside the app — the user
 * never types a URL or a key, the way a hosted agent simply arrives
 * connected. The gateway URL is a public constant baked into the build
 * (`BuildConfig.GATEWAY_URL`); the gateway key reaches the build only
 * through CI (`BuildConfig.GATEWAY_KEY`, from the HERMES_GATEWAY_KEY
 * Actions secret) and is never committed or shown in the UI.
 *
 * The baked connection applies ONLY while the user has never made an
 * explicit Hermes host choice. Any host-card save records one (the
 * [configured] flag), and the stored choice is then respected forever —
 * this resolver never overrides it. On a build whose baked key is empty
 * nothing here activates and the pre-v26 manual flow is unchanged.
 *
 * One v28 exception: the host card is gone, so the baked gateway is the
 * only legitimate remote host left. A stored remote URL whose host is
 * NOT the baked gateway's host ([isStaleRemoteUrl]) is a leftover from
 * the host-card era — a typo'd or dead address the user can no longer
 * see or fix — and is ignored in favour of the baked connection. A
 * stored URL on the same host, in any scheme/path shape, stands.
 */
internal object HermesGatewayPreconnect {

    /**
     * Whether an explicit host choice exists: the v26 configured flag
     * (set by every host-card save), or — for installs that predate the
     * flag — any stored trace of one. A stored remote mode, URL or vault
     * key can only exist because the user saved host settings, so each
     * counts as a choice even with the flag absent.
     */
    fun hasExplicitChoice(
        configured: Boolean,
        storedRemote: Boolean,
        storedUrl: String,
        storedKey: String,
    ): Boolean =
        configured || storedRemote || storedUrl.isNotBlank() || storedKey.isNotBlank()

    /** The baked connection is live only with a baked key AND no explicit choice on record. */
    fun isActive(
        configured: Boolean,
        storedRemote: Boolean,
        storedUrl: String,
        storedKey: String,
        bakedKey: String,
    ): Boolean =
        bakedKey.isNotBlank() && !hasExplicitChoice(configured, storedRemote, storedUrl, storedKey)

    /**
     * v28 stale-URL guard: a stored REMOTE config whose URL host differs
     * from the baked gateway's host. Only a remote config is judged (a
     * local "on this phone" choice keeps its inert stored URL), a blank
     * stored URL is not judged (pre-v28 behaviour stands), and the guard
     * needs a live baked connection (non-blank baked key and URL) to
     * fall back to. Hosts are compared after [HermesGatewayUrls.normalizeBaseUrl],
     * so scheme, port and path variations of the same host are NOT stale.
     */
    fun isStaleRemoteUrl(
        storedRemote: Boolean,
        storedUrl: String,
        bakedUrl: String,
        bakedKey: String,
    ): Boolean {
        if (!storedRemote || bakedKey.isBlank()) return false
        val storedHost = HermesGatewayUrls.hostOf(HermesGatewayUrls.normalizeBaseUrl(storedUrl))
        if (storedHost.isBlank()) return false
        val bakedHost = HermesGatewayUrls.hostOf(HermesGatewayUrls.normalizeBaseUrl(bakedUrl))
        return bakedHost.isNotBlank() && storedHost != bakedHost
    }

    /**
     * The host config a Hermes turn (and the host card's initial state)
     * actually uses. Unconfigured + baked key → the baked gateway:
     * remote, baked URL, baked key. Otherwise the stored choice stands
     * verbatim — except the key, where a user-entered vault key always
     * wins and the baked key only fills a blank (so a host card saved
     * against this same gateway works without re-typing the key the app
     * already carries). With an empty baked key the result is exactly
     * the stored config. The one override is the v28 stale-URL guard
     * ([isStaleRemoteUrl]): a stored remote URL on a foreign host is
     * ignored and the baked connection is used instead — with the same
     * key resolution, so a stored key still wins over the baked key.
     */
    fun resolve(
        configured: Boolean,
        storedRemote: Boolean,
        storedUrl: String,
        storedKey: String,
        bakedUrl: String,
        bakedKey: String,
    ): HermesHostConfig {
        val key = storedKey.ifBlank { bakedKey }
        val useBaked = isActive(configured, storedRemote, storedUrl, storedKey, bakedKey) ||
            isStaleRemoteUrl(storedRemote, storedUrl, bakedUrl, bakedKey)
        return if (useBaked) {
            HermesHostConfig(
                remote = true,
                baseUrl = HermesGatewayUrls.normalizeBaseUrl(bakedUrl),
                apiKey = key,
            )
        } else {
            HermesHostConfig(remote = storedRemote, baseUrl = storedUrl, apiKey = key)
        }
    }
}

internal object HermesGatewayRequests {
    /**
     * OpenAI chat-completions body for one turn: the prompt is the only
     * message (see class doc). A blank [model] (the profile leaves the
     * model UNSET, v24) omits the `model` field entirely so the
     * gateway's own `HERMES_MODEL` governs — sending an empty string
     * would hand the gateway a value it must reject or guess at.
     */
    fun chatCompletionBody(model: String, prompt: String): JSONObject {
        val body = JSONObject()
            .put("stream", true)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", prompt)),
            )
        if (model.isNotBlank()) body.put("model", model)
        return body
    }
}

/** One decoded frame of the gateway's chat-completions SSE stream. */
internal sealed interface HermesGatewayEvent {
    data class TextDelta(val text: String) : HermesGatewayEvent
    data class ReasoningDelta(val text: String) : HermesGatewayEvent
    data class ToolProgress(
        val tool: String,
        val label: String,
        val status: String,
        val toolCallId: String,
    ) : HermesGatewayEvent
    data class Status(val kind: String, val text: String) : HermesGatewayEvent

    /** The gateway asks for a tool approval; the remote UI for this is a later phase. */
    data object ApprovalRequested : HermesGatewayEvent

    /** Terminal chunk: [finishReason] is null only when the chunk carried none. */
    data class Finished(val finishReason: String?, val errorMessage: String?) : HermesGatewayEvent

    /** The `data: [DONE]` sentinel. */
    data object StreamEnded : HermesGatewayEvent
}

/**
 * Incremental SSE decoder. Feed it one line at a time (without the line
 * terminator); each call returns the events completed by that line — a
 * frame is dispatched by its terminating blank line. Malformed frames are
 * dropped, never thrown: the stream is untrusted server output.
 */
internal class HermesGatewaySseParser {
    private var pendingEvent: String? = null
    private val pendingData = StringBuilder()

    fun feed(line: String): List<HermesGatewayEvent> {
        when {
            line.isEmpty() -> return dispatch()
            line.startsWith(":") -> Unit // SSE comment / keep-alive.
            line.startsWith("event:") -> pendingEvent = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (pendingData.isNotEmpty()) pendingData.append('\n')
                pendingData.append(line.removePrefix("data:").trimStart())
            }
            else -> Unit // id: and unknown fields carry nothing the app needs.
        }
        return emptyList()
    }

    /** Flushes a trailing frame that arrived without its terminating blank line. */
    fun endOfStream(): List<HermesGatewayEvent> = dispatch()

    private fun dispatch(): List<HermesGatewayEvent> {
        val event = pendingEvent
        val data = pendingData.toString()
        pendingEvent = null
        pendingData.clear()
        if (data.isBlank()) return emptyList()
        if (data.trim() == "[DONE]") return listOf(HermesGatewayEvent.StreamEnded)
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return emptyList()
        return when (event) {
            "hermes.tool.progress" -> listOf(
                HermesGatewayEvent.ToolProgress(
                    tool = root.optString("tool"),
                    label = root.optString("label"),
                    status = root.optString("status"),
                    toolCallId = root.optString("toolCallId"),
                ),
            )
            "hermes.status" -> listOf(
                HermesGatewayEvent.Status(root.optString("kind"), root.optString("text")),
            )
            "approval.request" -> listOf(HermesGatewayEvent.ApprovalRequested)
            else -> parseChunk(root)
        }
    }

    private fun parseChunk(root: JSONObject): List<HermesGatewayEvent> {
        val events = mutableListOf<HermesGatewayEvent>()
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        delta?.optString("content")?.takeIf(String::isNotEmpty)
            ?.let { events += HermesGatewayEvent.TextDelta(it) }
        delta?.optString("reasoning_content")?.takeIf(String::isNotEmpty)
            ?.let { events += HermesGatewayEvent.ReasoningDelta(it) }
        val finishReason = choice?.opt("finish_reason") as? String
        if (finishReason != null) {
            val errorMessage = root.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
                ?: root.optJSONObject("hermes")?.optString("error")?.takeIf(String::isNotBlank)
            events += HermesGatewayEvent.Finished(finishReason, errorMessage)
        }
        return events
    }
}

internal object HermesGatewayErrors {
    /** The gateway's own error text from an OpenAI-style envelope, or null when the body carries none. */
    fun parseErrorMessage(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return when (val error = root.opt("error")) {
            is JSONObject -> error.optString("message").takeIf(String::isNotBlank)
            is String -> error.takeIf(String::isNotBlank)
            else -> null
        }
    }

    /**
     * A proxy in front of a sleeping free host answers 502/503/504 until
     * the service is up. Classification only: since v28 the chat path
     * never retries an HTTP response (see [HermesColdStartRetry]).
     */
    fun isRetryableStatus(code: Int): Boolean = code == 502 || code == 503 || code == 504

    fun isAuthStatus(code: Int): Boolean = code == 401 || code == 403
}

/**
 * v28 cold-start retry policy for one remote turn, extracted pure so the
 * schedule is unit-testable. A sleeping free host can take a minute to
 * boot, so a turn whose request dies at the network level — connect
 * timeout, reset, no HTTP response at all — BEFORE anything was delivered
 * is replayed up to [MAX_RETRIES] times, after 15s and then 30s, while
 * the turn's normal in-progress UI covers the wait. The stable per-turn
 * Idempotency-Key keeps a replay from ever running the turn twice on the
 * gateway.
 *
 * Two shapes are never replayed:
 *  - an HTTP response, whatever its status — it is a real answer from an
 *    awake host, and its error is reported as-is;
 *  - a failure after any stream event was delivered — replaying would
 *    duplicate output the user already saw.
 */
internal object HermesColdStartRetry {
    /** Retries after the initial attempt. */
    const val MAX_RETRIES = 2

    /** Total tries for one turn: the initial request plus the retries. */
    const val MAX_ATTEMPTS = 1 + MAX_RETRIES

    private val RETRY_DELAYS_MS = listOf(15_000L, 30_000L)

    /**
     * Whether a failed attempt may be replayed: only a network-level
     * failure (no HTTP response arrived) with zero stream events
     * delivered so far.
     */
    fun isReplayable(networkLevelFailure: Boolean, deliveredEvents: Int): Boolean =
        networkLevelFailure && deliveredEvents == 0

    /**
     * Wait before the next attempt after attempt #[attempt] (1-based)
     * failed replayably, or null once every attempt is used up.
     */
    fun delayAfterAttemptMs(attempt: Int): Long? =
        if (attempt < 1 || attempt >= MAX_ATTEMPTS) null else RETRY_DELAYS_MS.getOrNull(attempt - 1)
}

internal sealed interface HermesGatewayOutcome {
    data class Completed(val sessionId: String?, val finishReason: String?) : HermesGatewayOutcome
    data class Failed(val message: String, val sessionId: String? = null) : HermesGatewayOutcome
}

/**
 * Streaming chat client for one gateway host. Retry behaviour follows
 * [HermesColdStartRetry]: a turn whose request dies at the network level
 * before a single stream event was delivered is replayed while the host
 * wakes (free hosts sleep when idle and can take a minute to boot); an
 * HTTP response is a real answer and is never replayed, and once output
 * has flowed, a failure is terminal — the turn is never replayed
 * underneath the user. The stable per-turn Idempotency-Key makes even the
 * early retries safe against double execution on the gateway.
 */
internal class HermesGatewayClient {
    @Volatile private var activeConnection: HttpURLConnection? = null

    /** Severs the in-flight request, if any; called from the bridge's stop path. */
    fun disconnect() {
        runCatching { activeConnection?.disconnect() }
    }

    suspend fun streamChat(
        config: HermesHostConfig,
        model: String,
        prompt: String,
        sessionId: String,
        isStopped: () -> Boolean,
        onWaking: () -> Unit,
        onEvent: suspend (HermesGatewayEvent) -> Unit,
    ): HermesGatewayOutcome {
        val baseUrl = HermesGatewayUrls.normalizeBaseUrl(config.baseUrl)
        val bodyJson = HermesGatewayRequests.chatCompletionBody(model, prompt).toString()
        val idempotencyKey = UUID.randomUUID().toString()
        var lastError: Exception? = null
        for (attempt in 1..HermesColdStartRetry.MAX_ATTEMPTS) {
            if (isStopped()) return HermesGatewayOutcome.Failed(STOPPED_MESSAGE, sessionId)
            val result = try {
                streamOnce(baseUrl, config.apiKey, bodyJson, sessionId, idempotencyKey, isStopped, onEvent)
            } catch (error: Exception) {
                if (isStopped()) return HermesGatewayOutcome.Failed(STOPPED_MESSAGE, sessionId)
                // Connect/write failure before any response arrived:
                // the classic cold-start shape (the proxy resets while
                // the service boots). streamOnce only throws from its
                // setup phase, so nothing was delivered and the policy
                // allows a replay. Never swallow the cause (v28): log it
                // in full and keep it for the final failure message.
                Log.e(TAG, "Hermes gateway attempt $attempt failed before any response", error)
                lastError = error
                AttemptResult.Retryable()
            }
            when (result) {
                is AttemptResult.Done -> return result.outcome
                is AttemptResult.Retryable -> {
                    result.cause?.let { cause ->
                        Log.e(TAG, "Hermes gateway attempt $attempt dropped before any output", cause)
                        lastError = cause
                    }
                    val waitMs = HermesColdStartRetry.delayAfterAttemptMs(attempt) ?: break
                    onWaking()
                    // Chunked wait so a stop request lands within half a second.
                    repeat((waitMs / STOP_CHECK_SLICE_MS).toInt()) {
                        if (isStopped()) return HermesGatewayOutcome.Failed(STOPPED_MESSAGE, sessionId)
                        delay(STOP_CHECK_SLICE_MS.toLong())
                    }
                }
            }
        }
        return HermesGatewayOutcome.Failed(
            HermesRemoteMessages.unreachableDetail(
                host = HermesGatewayUrls.hostOf(baseUrl).ifBlank { baseUrl },
                error = lastError,
            ),
            sessionId,
        )
    }

    private sealed interface AttemptResult {
        data class Done(val outcome: HermesGatewayOutcome) : AttemptResult

        /**
         * Nothing was delivered to the caller, so replaying the turn
         * cannot duplicate output. [cause] carries the network failure
         * when the attempt produced one internally (a dropped stream
         * before any event), for diagnostics.
         */
        data class Retryable(val cause: Exception? = null) : AttemptResult
    }

    private suspend fun streamOnce(
        baseUrl: String,
        apiKey: String,
        bodyJson: String,
        sessionId: String,
        idempotencyKey: String,
        isStopped: () -> Boolean,
        onEvent: suspend (HermesGatewayEvent) -> Unit,
    ): AttemptResult {
        val connection = (URL(HermesGatewayUrls.chatCompletionsUrl(baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Idempotency-Key", idempotencyKey)
            if (sessionId.isNotBlank()) setRequestProperty("X-Hermes-Session-Id", sessionId)
        }
        activeConnection = connection
        try {
            connection.outputStream.use { stream -> stream.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val responseSessionId = connection.getHeaderField("X-Hermes-Session-Id")
                ?.takeIf(String::isNotBlank) ?: sessionId
            if (code != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                return when {
                    HermesGatewayErrors.isAuthStatus(code) -> AttemptResult.Done(
                        HermesGatewayOutcome.Failed(HermesRemoteMessages.KEY_REJECTED, responseSessionId),
                    )
                    // Any other HTTP response — 502/503/504 from a proxy
                    // included — is a real answer from an awake host and
                    // is never replayed (HermesColdStartRetry).
                    else -> AttemptResult.Done(
                        HermesGatewayOutcome.Failed(
                            HermesGatewayErrors.parseErrorMessage(errorBody)
                                ?.let { detail -> "The Hermes host reported an error: $detail" }
                                ?: HermesRemoteMessages.httpStatus(code),
                            responseSessionId,
                        ),
                    )
                }
            }
            val parser = HermesGatewaySseParser()
            var deliveredEvents = 0
            var finish: HermesGatewayEvent.Finished? = null
            var sawStreamEnd = false
            var readError: Exception? = null

            suspend fun handle(events: List<HermesGatewayEvent>) {
                for (event in events) {
                    when (event) {
                        is HermesGatewayEvent.Finished -> finish = event
                        HermesGatewayEvent.StreamEnded -> sawStreamEnd = true
                        else -> {
                            deliveredEvents++
                            onEvent(event)
                        }
                    }
                }
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            try {
                while (!sawStreamEnd) {
                    if (isStopped()) {
                        return AttemptResult.Done(HermesGatewayOutcome.Failed(STOPPED_MESSAGE, responseSessionId))
                    }
                    val line = reader.readLine() ?: break
                    handle(parser.feed(line))
                }
            } catch (error: Exception) {
                readError = error
            }
            if (readError == null) handle(parser.endOfStream())
            if (isStopped()) {
                return AttemptResult.Done(HermesGatewayOutcome.Failed(STOPPED_MESSAGE, responseSessionId))
            }
            val finished = finish
            return when {
                // A terminal chunk already arrived; a socket error after it
                // (the proxy closing the stream) does not fail the turn.
                finished?.errorMessage != null -> AttemptResult.Done(
                    HermesGatewayOutcome.Failed(finished.errorMessage, responseSessionId),
                )
                finished != null && (finished.finishReason == "stop" || finished.finishReason == "length") ->
                    AttemptResult.Done(HermesGatewayOutcome.Completed(responseSessionId, finished.finishReason))
                finished != null -> AttemptResult.Done(
                    HermesGatewayOutcome.Failed(
                        "The Hermes host ended the turn early (${finished.finishReason}).",
                        responseSessionId,
                    ),
                )
                readError != null &&
                    HermesColdStartRetry.isReplayable(networkLevelFailure = true, deliveredEvents = deliveredEvents) ->
                    AttemptResult.Retryable(cause = readError)
                readError != null || !sawStreamEnd -> AttemptResult.Done(
                    HermesGatewayOutcome.Failed(HermesRemoteMessages.DROPPED, responseSessionId),
                )
                else -> AttemptResult.Done(HermesGatewayOutcome.Completed(responseSessionId, null))
            }
        } finally {
            if (activeConnection === connection) activeConnection = null
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        private const val TAG = "HermesGatewayClient"
        private const val STOPPED_MESSAGE = "Stopped by user"
        private const val CONNECT_TIMEOUT_MS = 30_000

        /** Per-read gap tolerance: a host-side tool can run silently for minutes. */
        private const val READ_TIMEOUT_MS = 300_000
        private const val STOP_CHECK_SLICE_MS = 500
    }
}

internal sealed interface HermesHostProbe {
    data object Awake : HermesHostProbe
    data class Failed(val message: String) : HermesHostProbe
}

/**
 * Settings "Test connection" probe. `/health` needs no key and the request
 * itself wakes a sleeping host, so it is retried for about a minute; then
 * `/v1/models` (which requires the key) verifies the saved key actually
 * authenticates, so a healthy host with a wrong key is reported as such.
 */
internal suspend fun probeHermesHost(baseUrl: String, apiKey: String): HermesHostProbe {
    val normalized = HermesGatewayUrls.normalizeBaseUrl(baseUrl)
    if (normalized.isEmpty()) return HermesHostProbe.Failed("Enter the host URL first.")
    var healthy = false
    for (attempt in 1..HEALTH_ATTEMPTS) {
        if (hermesHostHttpGet(HermesGatewayUrls.healthUrl(normalized), null, PROBE_TIMEOUT_MS) == HttpURLConnection.HTTP_OK) {
            healthy = true
            break
        }
        if (attempt < HEALTH_ATTEMPTS) delay(HEALTH_RETRY_DELAY_MS)
    }
    if (!healthy) return HermesHostProbe.Failed(HermesRemoteMessages.UNREACHABLE)
    if (apiKey.isBlank()) return HermesHostProbe.Failed(HermesRemoteMessages.NO_KEY)
    val modelsCode = hermesHostHttpGet(HermesGatewayUrls.modelsUrl(normalized), apiKey, PROBE_TIMEOUT_MS)
    return when {
        modelsCode == HttpURLConnection.HTTP_OK -> HermesHostProbe.Awake
        HermesGatewayErrors.isAuthStatus(modelsCode) -> HermesHostProbe.Failed(HermesRemoteMessages.KEY_REJECTED)
        modelsCode <= 0 -> HermesHostProbe.Failed(HermesRemoteMessages.UNREACHABLE)
        else -> HermesHostProbe.Failed(HermesRemoteMessages.httpStatus(modelsCode))
    }
}

private const val HEALTH_ATTEMPTS = 6
private const val HEALTH_RETRY_DELAY_MS = 10_000L
private const val PROBE_TIMEOUT_MS = 20_000

/** Status code of a GET, or -1 when the request never got a response. */
private fun hermesHostHttpGet(url: String, apiKey: String?, timeoutMs: Int): Int = runCatching {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        if (!apiKey.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.responseCode
    } finally {
        connection.disconnect()
    }
}.getOrDefault(-1)
