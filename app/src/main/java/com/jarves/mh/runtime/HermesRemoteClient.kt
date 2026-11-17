package com.jarves.mh.runtime

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
    const val KEY_REJECTED = "The Hermes host rejected the gateway key. Check the key saved in Agent → Hermes host."
    const val NO_URL = "No Hermes host URL is saved. Add one in Agent → Hermes host."
    const val NO_KEY = "No Hermes host gateway key is saved. Add one in Agent → Hermes host."
    const val UNREACHABLE = "Could not reach the Hermes host. Check the host URL in Agent → Hermes host and that the host is running."
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

    fun chatCompletionsUrl(normalizedBaseUrl: String): String = "$normalizedBaseUrl/v1/chat/completions"
    fun modelsUrl(normalizedBaseUrl: String): String = "$normalizedBaseUrl/v1/models"
    fun healthUrl(normalizedBaseUrl: String): String = "$normalizedBaseUrl/health"
}

internal object HermesGatewayRequests {
    /** OpenAI chat-completions body for one turn: the prompt is the only message (see class doc). */
    fun chatCompletionBody(model: String, prompt: String): JSONObject = JSONObject()
        .put("model", model)
        .put("stream", true)
        .put(
            "messages",
            JSONArray().put(JSONObject().put("role", "user").put("content", prompt)),
        )
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

    /** A proxy in front of a sleeping free host answers 502/503/504 until the service is up. */
    fun isRetryableStatus(code: Int): Boolean = code == 502 || code == 503 || code == 504

    fun isAuthStatus(code: Int): Boolean = code == 401 || code == 403
}

internal sealed interface HermesGatewayOutcome {
    data class Completed(val sessionId: String?, val finishReason: String?) : HermesGatewayOutcome
    data class Failed(val message: String, val sessionId: String? = null) : HermesGatewayOutcome
}

/**
 * Streaming chat client for one gateway host. A turn whose request fails
 * before a single stream event was delivered is retried while the host
 * wakes (free hosts sleep when idle and can take a minute to boot); once
 * output has flowed, a failure is terminal — the turn is never replayed
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
        var lastFailureWasWaking = false
        for (attempt in 1..MAX_ATTEMPTS) {
            if (isStopped()) return HermesGatewayOutcome.Failed(STOPPED_MESSAGE, sessionId)
            val result = try {
                streamOnce(baseUrl, config.apiKey, bodyJson, sessionId, idempotencyKey, isStopped, onEvent)
            } catch (error: Exception) {
                if (isStopped()) return HermesGatewayOutcome.Failed(STOPPED_MESSAGE, sessionId)
                // Connect/read failure before any response: the classic
                // cold-start shape (proxy resets while the service boots).
                AttemptResult.Retryable(waking = false)
            }
            when (result) {
                is AttemptResult.Done -> return result.outcome
                is AttemptResult.Retryable -> {
                    lastFailureWasWaking = result.waking
                    if (attempt == MAX_ATTEMPTS) break
                    onWaking()
                    // Chunked wait so a stop request lands within half a second.
                    repeat(RETRY_DELAY_MS / STOP_CHECK_SLICE_MS) {
                        if (isStopped()) return HermesGatewayOutcome.Failed(STOPPED_MESSAGE, sessionId)
                        delay(STOP_CHECK_SLICE_MS.toLong())
                    }
                }
            }
        }
        return HermesGatewayOutcome.Failed(
            if (lastFailureWasWaking) HermesRemoteMessages.STILL_WAKING else HermesRemoteMessages.UNREACHABLE,
            sessionId,
        )
    }

    private sealed interface AttemptResult {
        data class Done(val outcome: HermesGatewayOutcome) : AttemptResult

        /** Nothing was delivered to the caller, so replaying the turn cannot duplicate output. */
        data class Retryable(val waking: Boolean) : AttemptResult
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
                    HermesGatewayErrors.isRetryableStatus(code) -> AttemptResult.Retryable(waking = true)
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
                readError != null && deliveredEvents == 0 -> AttemptResult.Retryable(waking = false)
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
        private const val STOPPED_MESSAGE = "Stopped by user"
        private const val CONNECT_TIMEOUT_MS = 30_000

        /** Per-read gap tolerance: a host-side tool can run silently for minutes. */
        private const val READ_TIMEOUT_MS = 300_000
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 20_000
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
