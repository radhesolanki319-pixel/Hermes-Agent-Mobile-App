package com.jarves.mh.runtime

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure core of the v32 warm-Hermes transport: the embedded Hermes runs
 * as a persistent local API server inside the guest (Hermes 0.19.0's own
 * `gateway/platforms/api_server.py`, started with only the api_server
 * platform enabled — see [HermesWarmServer]) and turns are served over
 * loopback HTTP with SSE streaming instead of a fresh
 * `hermes chat -q -Q` process per message.
 *
 * Why this transport (verified against the pinned 0.19.0 wheel, not the
 * dev clone):
 *  - The API server is the release's designed streaming surface:
 *    `POST /v1/chat/completions` with `stream: true` answers
 *    `chat.completion.chunk` SSE frames, and its capabilities payload
 *    advertises `chat_completions_streaming: True`. Conversation
 *    continuity is the `X-Hermes-Session-Id` header, backed by the same
 *    `state.db` SessionDB the CLI's `--resume` uses, so stored
 *    conversation ids keep their meaning across both transports.
 *  - The ACP server (`hermes-acp`, the [acp] extra the app already
 *    installs) would work too, but it is a stdio JSON-RPC protocol for
 *    editor integrations: driving it means a full JSON-RPC client and
 *    session negotiation in the bridge for a strictly worse fit with
 *    the existing RuntimeEvent flow, when the wheel's HTTP surface is
 *    already proven in this codebase (the Phase 4 gateway client spoke
 *    the identical wire protocol to the same server code).
 *  - The CLI itself cannot stream in 0.19.0: `hermes_cli/oneshot.py`
 *    explicitly nulls the agent's stream-delta callback for one-shot
 *    runs ("make sure AIAgent doesn't invoke any streaming"), and
 *    `-Q` prints only the final response at process end. There is no
 *    streaming flag to turn on.
 *
 * Everything in this file is stdlib + org.json so the wire logic is
 * unit-tested off-device; the Android/HTTP/process half lives in
 * [HermesWarmServer].
 */

/** Loopback endpoint of the in-guest API server (its own defaults). */
internal object HermesWarmEndpoint {
    const val HOST = "127.0.0.1"
    const val PORT = 8642
    const val BASE_URL = "http://$HOST:$PORT"
    const val CHAT_COMPLETIONS_URL = "$BASE_URL/v1/chat/completions"
    const val MODELS_URL = "$BASE_URL/v1/models"
    const val HEALTH_URL = "$BASE_URL/health"

    fun sessionUrl(sessionId: String): String = "$BASE_URL/api/sessions/${encodePathSegment(sessionId)}"

    /** RFC 3986 path-segment encoding (session ids are plain slugs in practice; this is belt-and-braces). */
    private fun encodePathSegment(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append(String.format("%02X", byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }
}

/**
 * The bearer key for the local API server. Hermes refuses to start the
 * API server without a key of at least 16 characters even on loopback
 * (it dispatches terminal-capable agent work), and loopback inside
 * PRoot is the phone's shared loopback — other apps could reach the
 * port — so the key is a real secret: generated once by the app, kept
 * in app-private storage, never shown in the UI and never sent anywhere
 * but this loopback endpoint.
 */
internal object HermesWarmServerKey {
    private const val MIN_LENGTH = 16

    /** A fresh key: 64 hex characters from two random UUIDs. */
    fun generate(): String =
        UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")

    /** Whether [key] is shaped like one of ours / strong enough for Hermes's startup guard. */
    fun isUsable(key: String): Boolean =
        key.length >= MIN_LENGTH && key.none(Char::isWhitespace)
}

/**
 * What a warm server process is bound to: the project workspace (its
 * TERMINAL_CWD and bind mounts) and the provider route it was started
 * with. The gateway resolves the provider key from its process
 * environment (the config's `key_env` names a variable the process was
 * started with), so a different secret — or a different workspace,
 * provider or endpoint — needs a fresh server process. The model is
 * deliberately NOT part of the fingerprint: the server re-reads
 * config.yaml's model section for every request, so a model change
 * applies to the running server with no restart.
 *
 * The secret itself is never stored here — only its SHA-256 digest, so
 * fingerprints can be compared (and logged) without holding the key.
 */
internal data class HermesWarmFingerprint(
    val guestWorkspacePath: String,
    val provider: String,
    val baseUrl: String,
    val custom: Boolean,
    val secretDigest: String,
) {
    companion object {
        fun of(guestWorkspacePath: String, route: HermesRoute, secret: String): HermesWarmFingerprint =
            HermesWarmFingerprint(
                guestWorkspacePath = guestWorkspacePath,
                provider = route.provider,
                baseUrl = route.baseUrl,
                custom = route.custom,
                secretDigest = sha256Hex(secret),
            )

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** Result of probing the server for a stored session id. */
internal enum class HermesSessionProbe { EXISTS, MISSING, UNKNOWN }

/** How one warm turn addresses conversation continuity. */
internal sealed interface HermesSessionPlan {
    /** Continue the stored session; the server loads its history from state.db. */
    data class Resume(val sessionId: String) : HermesSessionPlan

    /** Start fresh; the prompt carries the history embed, exactly like a CLI first turn. */
    data object Fresh : HermesSessionPlan
}

internal object HermesSessionPlanner {
    /**
     * Mirrors the CLI path's semantics: a stored id the server still
     * knows resumes (no history embed — the store holds the turns);
     * anything else starts fresh WITH the embed, so the model keeps the
     * conversation's context even when the store lost the session. The
     * CLI fails such a turn outright and clears the id; the warm path
     * degrades to the same place the CLI lands on the following turn.
     * A failed probe (UNKNOWN) also plans Fresh: the embed keeps the
     * turn correct, and a genuinely dead server fails the chat request
     * itself, which the fallback policy handles.
     */
    fun plan(storedId: String?, probe: HermesSessionProbe): HermesSessionPlan {
        val id = storedId?.takeIf(String::isNotBlank) ?: return HermesSessionPlan.Fresh
        return if (probe == HermesSessionProbe.EXISTS) {
            HermesSessionPlan.Resume(id)
        } else {
            HermesSessionPlan.Fresh
        }
    }
}

/** One decoded frame of the API server's chat-completions SSE stream. */
internal sealed interface HermesLocalEvent {
    data class TextDelta(val text: String) : HermesLocalEvent
    data class ReasoningDelta(val text: String) : HermesLocalEvent
    data class ToolProgress(
        val tool: String,
        val label: String,
        val status: String,
        val toolCallId: String,
    ) : HermesLocalEvent
    data class Status(val kind: String, val text: String) : HermesLocalEvent

    /** The server asks for a tool approval; with the app's approvals-off config this does not occur. */
    data object ApprovalRequested : HermesLocalEvent

    /** Terminal chunk: [finishReason] is null only when the chunk carried none. */
    data class Finished(val finishReason: String?, val errorMessage: String?) : HermesLocalEvent

    /** The `data: [DONE]` sentinel. */
    data object StreamEnded : HermesLocalEvent
}

/**
 * Incremental SSE decoder for the API server's stream — the identical
 * wire protocol the Phase 4 gateway client decoded against the same
 * server code (frame shapes read from `gateway/platforms/
 * api_server.py`): `data: <json>` lines of `chat.completion.chunk`
 * frames terminated by `data: [DONE]`, plus named frames
 * `hermes.tool.progress`, `hermes.status` and `approval.request`.
 * Feed it one line at a time (without the line terminator); each call
 * returns the events completed by that line — a frame is dispatched by
 * its terminating blank line. Malformed frames are dropped, never
 * thrown: the stream is untrusted server output.
 */
internal class HermesLocalSseParser {
    private var pendingEvent: String? = null
    private val pendingData = StringBuilder()

    fun feed(line: String): List<HermesLocalEvent> {
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
    fun endOfStream(): List<HermesLocalEvent> = dispatch()

    private fun dispatch(): List<HermesLocalEvent> {
        val event = pendingEvent
        val data = pendingData.toString()
        pendingEvent = null
        pendingData.clear()
        if (data.isBlank()) return emptyList()
        if (data.trim() == "[DONE]") return listOf(HermesLocalEvent.StreamEnded)
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return emptyList()
        return when (event) {
            "hermes.tool.progress" -> listOf(
                HermesLocalEvent.ToolProgress(
                    tool = root.optString("tool"),
                    label = root.optString("label"),
                    status = root.optString("status"),
                    toolCallId = root.optString("toolCallId"),
                ),
            )
            "hermes.status" -> listOf(
                HermesLocalEvent.Status(root.optString("kind"), root.optString("text")),
            )
            "approval.request" -> listOf(HermesLocalEvent.ApprovalRequested)
            else -> parseChunk(root)
        }
    }

    private fun parseChunk(root: JSONObject): List<HermesLocalEvent> {
        val events = mutableListOf<HermesLocalEvent>()
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        delta?.optString("content")?.takeIf(String::isNotEmpty)
            ?.let { events += HermesLocalEvent.TextDelta(it) }
        delta?.optString("reasoning_content")?.takeIf(String::isNotEmpty)
            ?.let { events += HermesLocalEvent.ReasoningDelta(it) }
        val finishReason = choice?.opt("finish_reason") as? String
        if (finishReason != null) {
            val errorMessage = root.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
                ?: root.optJSONObject("hermes")?.optString("error")?.takeIf(String::isNotBlank)
            events += HermesLocalEvent.Finished(finishReason, errorMessage)
        }
        return events
    }
}

internal object HermesLocalRequests {
    /**
     * OpenAI chat-completions body for one turn: the prompt is the only
     * message (it already carries the project wrap, any history embed,
     * the environment brief and the generative-ui block — the same text
     * the CLI path passes to `-q`). A blank [model] (the profile leaves
     * the model UNSET, v24) omits the `model` field entirely so the
     * server's own config resolution governs — sending an empty string
     * would hand it a value it must reject or guess at.
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

internal object HermesLocalErrors {
    /** The server's own error text from an OpenAI-style envelope, or null when the body carries none. */
    fun parseErrorMessage(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return when (val error = root.opt("error")) {
            is JSONObject -> error.optString("message").takeIf(String::isNotBlank)
            is String -> error.takeIf(String::isNotBlank)
            else -> null
        }
    }
}

/**
 * Turns the raw SSE content deltas of one warm turn into the exact text
 * the CLI path would have produced for the same reply, incrementally:
 * every snapshot re-runs the shared [HermesQuietOutput.Collector] over
 * the text received so far — completed lines plus the pending partial
 * line — so reasoning boxes, `<think>` segments, diagnostic lines and
 * the session trailer are classified identically on both transports,
 * while the pending line's text still streams token by token instead
 * of waiting for its newline. [feed] returns the newly visible suffix
 * to show in the live message ("" when the new content is not yet
 * displayable — content the classifier is holding inside a reasoning
 * segment, or a think tag split across deltas, whose first half the
 * speculative pass deliberately holds back).
 *
 * Re-classifying from scratch per snapshot is O(reply²) in the worst
 * case; chat replies are small, and past [SPECULATION_LIMIT] chars the
 * pending line stops being speculated (completed lines still stream).
 *
 * The prefix invariant the live UI relies on: the collector's visible
 * text only ever grows by appends (accepted lines are never rewritten),
 * so each snapshot extends the previously emitted text and the suffix
 * is always well-defined. If a snapshot ever failed to extend it
 * ([diverged] — e.g. a line whose classification changed as it
 * completed), live emission stops rather than showing text the final
 * classification would not produce; the turn's canonical text remains
 * available from [outcome].
 */
internal class HermesStreamAssembler {
    private val completedLines = mutableListOf<String>()
    private val lineBuffer = StringBuilder()
    private var rawLength = 0
    private var emitted = ""

    var diverged: Boolean = false
        private set

    /** The canonical classification of everything fed so far. */
    val outcome: HermesQuietOutput.Outcome get() = classify()

    /** Text emitted so far; equals [outcome]'s response text unless [diverged]. */
    val emittedText: String get() = emitted

    fun feed(delta: String): String {
        if (delta.isEmpty()) return ""
        rawLength += delta.length
        lineBuffer.append(delta)
        var newline = lineBuffer.indexOf("\n")
        while (newline >= 0) {
            completedLines += lineBuffer.substring(0, newline).trimEnd('\r')
            lineBuffer.delete(0, newline + 1)
            newline = lineBuffer.indexOf("\n")
        }
        return newlyVisible()
    }

    /** Flushes the trailing partial line (if any) and returns the last visible suffix. */
    fun finish(): String {
        if (lineBuffer.isNotEmpty()) {
            completedLines += lineBuffer.toString().trimEnd('\r')
            lineBuffer.clear()
        }
        return newlyVisible()
    }

    private fun classify(): HermesQuietOutput.Outcome {
        val collector = HermesQuietOutput.Collector()
        completedLines.forEach(collector::accept)
        val pending = speculativePending()
        if (pending.isNotEmpty()) collector.accept(pending)
        return collector.finish()
    }

    /**
     * The pending partial line as the speculative pass may show it:
     * held back entirely once the reply grows past [SPECULATION_LIMIT],
     * and truncated at a trailing half-typed think tag — a `<thi` at
     * the end of the stream so far must not leak as answer text before
     * the tag completes and the segment is classified as reasoning.
     */
    private fun speculativePending(): String {
        if (lineBuffer.isEmpty() || rawLength > SPECULATION_LIMIT) return ""
        var pending = lineBuffer.toString().trimEnd('\r')
        val lt = pending.lastIndexOf('<')
        if (lt >= 0) {
            val tail = pending.substring(lt)
            // Truncate a trailing partial think tag so it does not leak as answer
            // text before the tag completes and the collector classifies it as reasoning.
            // Matches the think tag variants recognized by HermesQuietOutput:
            // <think>, <thinking>, <reasoning>, <thought>, <REASONING_SCRATCHPAD>
            // and their closing tags. Check for complete tags, partial tags, and
            // common prefixes to prevent leakage of half-typed tags.
            val isThinkTagPrefix = tail.startsWith("<think") || tail.startsWith("<thinking")
                || tail.startsWith("<reasoning") || tail.startsWith("<thought")
                || tail.startsWith("<reasoning_scratchpad")
                || tail.startsWith("</think") || tail.startsWith("</thinking")
                || tail.startsWith("</reasoning") || tail.startsWith("</thought")
                || tail.startsWith("</reasoning_scratchpad")
            // Also check if the tail IS a prefix of a think tag (e.g., "<thi", "</th")
            val isPartialThinkTag = "<think".startsWith(tail) || "<thinking".startsWith(tail)
                || "<reasoning".startsWith(tail) || "<thought".startsWith(tail)
                || "<reasoning_scratchpad".startsWith(tail)
                || "</think".startsWith(tail) || "</thinking".startsWith(tail)
                || "</reasoning".startsWith(tail) || "</thought".startsWith(tail)
                || "</reasoning_scratchpad".startsWith(tail)
            if (isThinkTagPrefix || isPartialThinkTag) {
                pending = pending.substring(0, lt)
            }
        }
        return pending
    }

    private fun newlyVisible(): String {
        if (diverged) return ""
        val snapshot = classify().responseText
        if (!snapshot.startsWith(emitted)) {
            diverged = true
            return ""
        }
        val suffix = snapshot.substring(emitted.length)
        emitted = snapshot
        return suffix
    }

    private companion object {
        const val SPECULATION_LIMIT = 32_768
    }
}

/** How one warm turn ended, as the bridge consumes it. */
internal sealed interface HermesWarmTurnOutcome {
    /** The turn completed; [text] is the canonical classified reply (possibly blank for a tool-only turn). */
    data class Completed(val sessionId: String?, val text: String) : HermesWarmTurnOutcome

    /**
     * The server answered but the turn failed. [errorText] is the
     * server's own explanation (an error envelope or stream error),
     * shaped into the user-facing reason by the bridge through the same
     * [HermesQuietOutput.failureMessage] path the CLI uses.
     * [deliveredText] records whether any reply text already reached
     * the chat — it drives the fallback policy.
     */
    data class Failed(val errorText: String, val sessionId: String?, val deliveredText: Boolean) : HermesWarmTurnOutcome

    /**
     * No HTTP response ever arrived — the server is down, still
     * starting, or the connection died before it answered. Nothing was
     * delivered and the turn provably never ran, so the CLI path can
     * take over safely.
     */
    data object Unavailable : HermesWarmTurnOutcome

    /** The user stopped the turn. */
    data object Stopped : HermesWarmTurnOutcome
}

internal object HermesWarmFallback {
    /**
     * When a warm attempt hands the turn to the CLI path: whenever the
     * attempt produced nothing the user has seen — the server never
     * answered ([HermesWarmTurnOutcome.Unavailable]) or it failed
     * before any reply text streamed. A failure AFTER text was
     * delivered ends the turn instead: re-running the whole turn on the
     * CLI would append a second, duplicate reply under the partial one
     * and could repeat tool side effects the server already performed.
     * (The same never-replay-after-delivery rule the Phase 4 gateway
     * client followed.)
     */
    fun shouldUseCli(outcome: HermesWarmTurnOutcome): Boolean = when (outcome) {
        HermesWarmTurnOutcome.Unavailable -> true
        is HermesWarmTurnOutcome.Failed -> !outcome.deliveredText
        is HermesWarmTurnOutcome.Completed -> false
        HermesWarmTurnOutcome.Stopped -> false
    }
}

/** Timing policy for the warm server: start budget, failure cooldown, idle stop. */
internal object HermesWarmTiming {
    /** How long a turn waits for a starting server before the CLI path takes the turn. */
    const val START_WAIT_BUDGET_MS = 15_000L

    /** After a failed start, turns go straight to the CLI for this long instead of paying the wait again. */
    const val FAILURE_COOLDOWN_MS = 30_000L

    /** A warm server with no Hermes turn for this long is stopped; the next turn restarts it. */
    const val IDLE_STOP_MS = 10 * 60_000L

    const val HEALTH_POLL_INTERVAL_MS = 150L

    fun inCooldown(lastFailureAtMs: Long, nowMs: Long): Boolean =
        lastFailureAtMs > 0L && nowMs - lastFailureAtMs < FAILURE_COOLDOWN_MS

    fun idleExpired(lastUsedAtMs: Long, nowMs: Long): Boolean =
        lastUsedAtMs > 0L && nowMs - lastUsedAtMs >= IDLE_STOP_MS
}

/**
 * Terminal classification of a finished SSE read, mirroring the rules
 * the Phase 4 client proved against the same server: a terminal chunk
 * carrying an error fails the turn with that error; `stop`/`length`
 * complete it; any other finish reason ended it early; a read failure
 * or a stream that closed without its terminal markers is a drop.
 */
internal object HermesWarmStreamEnd {
    sealed interface Verdict {
        data object Completed : Verdict
        data class Failed(val message: String) : Verdict
        data object Dropped : Verdict
    }

    fun verdict(
        finishReason: String?,
        finishError: String?,
        sawStreamEnd: Boolean,
        readFailed: Boolean,
    ): Verdict = when {
        finishError != null -> Verdict.Failed(finishError)
        finishReason == "stop" || finishReason == "length" -> Verdict.Completed
        finishReason != null -> Verdict.Failed("Hermes ended the turn early ($finishReason).")
        readFailed || !sawStreamEnd -> Verdict.Dropped
        else -> Verdict.Completed
    }
}
