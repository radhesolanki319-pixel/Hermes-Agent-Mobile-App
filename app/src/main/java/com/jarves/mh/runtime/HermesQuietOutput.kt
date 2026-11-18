package com.jarves.mh.runtime

/**
 * Line classifier for the embedded Hermes transport: `hermes chat -q <prompt> -Q`
 * against Hermes **0.19.0**, the release the app installs (RuntimeInstaller's pin;
 * it is also the newest release on PyPI).
 *
 * Why not the stream-json protocol the bridge was first written against?
 * `--format stream-json` and `--query-file` exist only in post-0.19.0 Hermes
 * source; 0.19.0's argparse rejects both (`hermes: error: unrecognized
 * arguments: --query-file … --format stream-json`, process exit 2, empty
 * stdout). Every on-device turn died at argument parsing before Hermes
 * started, and the bridge — which discarded stderr — could only report a
 * bare "exited with code 2". The quiet protocol below is 0.19.0's documented
 * automation surface (its own code steers wrappers to
 * `$(hermes chat -Q --resume <id> -q "…")`).
 *
 * The 0.19.0 quiet contract, verified by running the pinned release against
 * a local mock provider with the exact config this app writes:
 *
 *  - stdout carries ONLY the final response text, printed once at the end —
 *    plus, in practice, diagnostic lines Hermes prints while starting
 *    (e.g. the `⚠ tirith security scanner …` warning when that optional
 *    scanner is absent, which it is in the app's runtime bundle).
 *  - A FAILED turn leaves its explanation as that stdout text instead of a
 *    reply (`HTTP 401: Invalid API key provided`,
 *    `HTTP 404: model 'x' not found`,
 *    `API call failed after 3 retries: Connection error.`), and the
 *    process exits 1. Agent-init failures print
 *    `Failed to initialize agent: <reason>` (stdout) and also exit 1.
 *  - stderr carries the session trailer `\nsession_id: <id>` (present even
 *    for failed turns), a `↻ Resumed session <id> (…)` banner on resume,
 *    `Error: <reason>` when a failed turn produced no response text, and
 *    `Session not found: <id>` for an unknown `--resume` id (exit 1).
 *  - argparse failures print a `usage: hermes …` block and a final
 *    `hermes: error: <reason>` line to stderr and exit 2 with no stdout.
 *
 * The native launcher merges stdout and stderr into one capture file, so
 * classification is per line and order-independent: known diagnostic lines
 * are pulled out wherever they land, and the remaining lines, in order,
 * are the response. This type is pure (stdlib only) so the classification
 * is unit-tested against the captured real outputs.
 *
 * One leak the contract above does not promise but the field produced
 * (v22): the model's reasoning can ride inside the response text framed
 * as Hermes CLI's own reasoning box — a `┌─ Reasoning ─…─┐` header, the
 * reasoning lines, and a `└─…─┘` footer (the frame `cli.py` draws around
 * thinking, in `_stream_reasoning_delta` / the post-turn display) — or,
 * equivalently, wrapped in `<think>`-style tags. The user saw the box,
 * reasoning paragraphs and all, as the chat reply to a plain greeting.
 * Reasoning is not answer text: the Collector drops the framed box and
 * any tag-wrapped thinking segment before classification, so only the
 * final answer is ever stored as the assistant message.
 */
internal object HermesQuietOutput {

    data class Outcome(
        /** The turn's text: the reply on success, the failure explanation on most failures. */
        val responseText: String,
        /** Session id from the `session_id:` trailer, when Hermes printed one. */
        val sessionId: String?,
        /** `Error:` / init-failure / session-not-found text, when present. */
        val errorText: String?,
        /** The reason from an argparse `hermes: error:` line, when the run never started. */
        val usageError: String?,
        /** Last content line of a Python traceback, when one was printed. */
        val tracebackTail: String?,
    )

    class Collector {
        private val responseLines = mutableListOf<String>()
        private var sessionId: String? = null
        private var errorText: String? = null
        private var usageError: String? = null
        private var sawTraceback = false
        private var inUsageBlock = false
        private var lastContentLine: String? = null
        private var inReasoningBox = false
        private var inThinkBlock = false

        /**
         * The line with any reasoning segment removed, or null when the
         * line is reasoning (or inside reasoning) and must be dropped:
         * the framed `┌─ Reasoning ─…─┐` box (frame lines are matched
         * after stripping ANSI escapes — the CLI wraps them in dim
         * codes) and `<think>`-style tag blocks, including blocks that
         * span lines or share a line with answer text.
         */
        private fun visibleLine(rawLine: String): String? {
            val plain = ANSI_ESCAPE.replace(rawLine, "").trim()
            if (inReasoningBox) {
                if (REASONING_BOX_CLOSE.matches(plain)) inReasoningBox = false
                return null
            }
            if (REASONING_BOX_OPEN.matches(plain)) {
                inReasoningBox = true
                return null
            }
            var rest = rawLine
            if (inThinkBlock) {
                val close = THINK_CLOSE.find(rest) ?: return null
                rest = rest.substring(close.range.last + 1)
                inThinkBlock = false
            }
            while (true) {
                val open = THINK_OPEN.find(rest) ?: break
                val after = rest.substring(open.range.last + 1)
                val close = THINK_CLOSE.find(after)
                if (close == null) {
                    rest = rest.substring(0, open.range.first)
                    inThinkBlock = true
                    break
                }
                rest = rest.substring(0, open.range.first) + after.substring(close.range.last + 1)
            }
            return rest
        }

        fun accept(rawLine: String) {
            val line = visibleLine(rawLine) ?: return
            val trimmed = line.trim()
            if (inUsageBlock) {
                // Argparse usage dump: continuation lines are indented (or
                // the `usage:` header of a nested parser). The first
                // flush-left line ends the block and is classified normally.
                if (line.firstOrNull()?.isWhitespace() == true || trimmed.startsWith("usage:")) return
                inUsageBlock = false
            }
            when {
                trimmed.isEmpty() -> responseLines += line
                SESSION_ID_LINE.matchEntire(trimmed)?.let { sessionId = it.groupValues[1] } != null -> Unit
                // An argparse dump can only precede any real output; a
                // reply line that happens to start with "usage:" later in
                // the stream stays response text.
                trimmed.startsWith("usage:") && responseLines.all { it.isBlank() } -> inUsageBlock = true
                ARGPARSE_ERROR_LINE.matchEntire(trimmed)?.let { usageError = it.groupValues[1] } != null -> Unit
                trimmed.startsWith("⚠") || trimmed.startsWith("↻") || trimmed.startsWith("Warning:") ||
                    trimmed.startsWith("Use a session ID from a previous CLI run") -> Unit
                ERROR_LINE.matchEntire(trimmed)?.let { if (errorText == null) errorText = it.groupValues[1] } != null -> Unit
                INIT_FAILURE_LINE.containsMatchIn(trimmed) || SESSION_NOT_FOUND_LINE.containsMatchIn(trimmed) -> {
                    if (errorText == null) errorText = trimmed
                }
                trimmed.startsWith("Traceback (most recent call last):") -> sawTraceback = true
                else -> {
                    responseLines += line
                    lastContentLine = trimmed
                }
            }
        }

        fun finish(): Outcome = Outcome(
            responseText = responseLines.joinToString("\n").trim(),
            sessionId = sessionId,
            errorText = errorText,
            usageError = usageError,
            tracebackTail = if (sawTraceback) lastContentLine else null,
        )
    }

    /**
     * The failure reason for a turn that produced no terminal success:
     * Hermes's own words whenever it left any, never a bare exit code
     * unless Hermes printed nothing at all. [providerError] is the
     * auth/quota pattern detector's verdict and keeps its old priority.
     */
    fun failureMessage(outcome: Outcome, exitCode: Int, providerError: String?): String =
        providerError
            ?: outcome.usageError?.let { "Hermes Agent could not start: $it" }
            ?: outcome.tracebackTail
            // An explicit `Error:`/init/session diagnostic outranks the
            // response bucket: in the quiet protocol the two never carry a
            // real reply at the same time (the `Error:` print fires only
            // when the turn produced no response text), and the bucket can
            // hold follow-on hint lines the diagnostic refers to.
            ?: outcome.errorText
            ?: outcome.responseText.takeIf(String::isNotBlank)
            ?: "Hermes Agent stopped with exit code $exitCode"

    private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    /** `┌─ Reasoning ───┐` — the frame header cli.py draws around thinking. */
    private val REASONING_BOX_OPEN = Regex("^┌─ Reasoning ─*┐$")
    /** `└───┘` — the frame footer. */
    private val REASONING_BOX_CLOSE = Regex("^└─*┘$")
    /** The tag set Hermes itself suppresses during streaming (cli.py). */
    private val THINK_OPEN = Regex("<(REASONING_SCRATCHPAD|think|thinking|reasoning|thought)>", RegexOption.IGNORE_CASE)
    private val THINK_CLOSE = Regex("</(REASONING_SCRATCHPAD|think|thinking|reasoning|thought)>", RegexOption.IGNORE_CASE)

    private val SESSION_ID_LINE = Regex("^session_id:\\s*(\\S+)\\s*$")
    private val ERROR_LINE = Regex("^Error:\\s*(.+)$")
    private val ARGPARSE_ERROR_LINE = Regex("^hermes: error:\\s*(.+)$")
    private val INIT_FAILURE_LINE = Regex("^Failed to initialize")
    private val SESSION_NOT_FOUND_LINE = Regex("^Session not found:")
}

/** UTF-8 size of [text] in bytes. */
internal fun utf8ByteCount(text: String): Int = text.toByteArray(Charsets.UTF_8).size

/**
 * Truncates [text] to at most [maxBytes] UTF-8 bytes without splitting a
 * multi-byte sequence. Used to keep the `-q` prompt under the kernel's
 * per-argument exec limit (128 KiB including the NUL terminator).
 */
internal fun truncateToUtf8Bytes(text: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}
