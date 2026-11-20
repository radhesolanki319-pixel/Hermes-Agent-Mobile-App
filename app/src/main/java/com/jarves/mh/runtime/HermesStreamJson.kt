package com.jarves.mh.runtime

import org.json.JSONObject

/**
 * Parser for Hermes's `--format stream-json` output (Hermes Agent
 * v0.23.2's CLI protocol for one-shot `-q` turns; the format did not
 * exist in the previously pinned 0.19.0, whose quiet `-Q` text contract
 * [HermesQuietOutput] still covers the final fallback path).
 *
 * The protocol is newline-delimited JSON on stdout:
 *  - `{"type":"system","subtype":"init","model":…,"session_id":…}` —
 *    emitted first, even when startup then fails, so a failed start
 *    still closes the protocol with init + result instead of silence.
 *  - `{"type":"text","text":…}` — answer deltas; concatenating every
 *    `text` field reproduces the reply byte for byte.
 *  - `{"type":"tool_use","name":…}` / `{"type":"tool_result",…}` —
 *    tool activity. Chat renders no tool rows (user directive), so
 *    these only feed the foreground progress label.
 *  - `{"type":"result","session_id":…,"exit_code":N,"text":…}` — the
 *    terminal record; `text` is the whole reply (authoritative when it
 *    carries `replay_gap`, i.e. the delta stream fell behind).
 *
 * The process capture merges stderr into the same stream, so non-JSON
 * lines (the `session_id:` trailer, diagnostics) are expected: they are
 * ignored here and only mined for provider errors by the caller.
 *
 * Streamed text passes through the same discipline as the other
 * transports: reasoning must never reach the chat. The emitter forwards
 * the agent's answer channel only, but a provider can still inline
 * `<think>`-style blocks in its answer text, so deltas are released
 * through a filter that strips the same tag set [HermesQuietOutput]
 * strips (`think`, `thinking`, `reasoning`, `thought`,
 * `REASONING_SCRATCHPAD`). The filter is append-only safe: it withholds
 * a trailing open block or partial tag until later deltas resolve it,
 * so what has been emitted is never retracted.
 */
internal class HermesStreamJsonCollector(
    private val onTextDelta: (String) -> Unit,
    private val onToolActivity: (String) -> Unit,
) {
    private var sawInit = false
    private var sawResult = false
    private var initSessionId: String? = null
    private var resultSessionId: String? = null
    private var resultExitCode: Int? = null
    private var resultText: String? = null
    private var resultError: String? = null
    private var replayGap = false

    private val rawText = StringBuilder()
    private val filteredText = StringBuilder()

    /** True once any protocol event has been seen. */
    val delivered: Boolean get() = sawInit || sawResult || rawText.isNotEmpty()

    /** The session id from the result record, else from init, else null. */
    val sessionId: String? get() = resultSessionId ?: initSessionId

    /** The full filtered reply streamed so far. */
    val streamedText: String get() = filteredText.toString()

    fun accept(line: String) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return
        when (json.optString("type")) {
            "system" -> {
                if (json.optString("subtype") == "init") {
                    sawInit = true
                    initSessionId = json.optString("session_id").takeIf(String::isNotBlank)
                }
            }
            "text" -> {
                val delta = json.optString("text")
                if (delta.isNotEmpty()) {
                    rawText.append(delta)
                    releaseFiltered()
                }
            }
            "tool_use" -> {
                val name = json.optString("name")
                if (name.isNotBlank()) onToolActivity(name)
            }
            "result" -> {
                sawResult = true
                resultSessionId = json.optString("session_id").takeIf(String::isNotBlank)
                resultExitCode = if (json.has("exit_code")) json.optInt("exit_code") else null
                resultText = json.optString("text")
                resultError = json.optString("error").takeIf(String::isNotBlank)
                replayGap = json.optBoolean("replay_gap", false)
            }
        }
    }

    /**
     * Recomputes the filtered view of [rawText] and emits the part that
     * has not been emitted yet. A trailing think block that has not
     * closed yet — or a trailing partial tag — is withheld: it may still
     * turn out to be reasoning (stripped) or plain text (released by a
     * later delta or by [finish]).
     */
    private fun releaseFiltered() {
        val filtered = filterThinkBlocks(rawText.toString(), final = false)
        if (filtered.length > filteredText.length && filtered.startsWith(filteredText.toString())) {
            val suffix = filtered.substring(filteredText.length)
            filteredText.append(suffix)
            onTextDelta(suffix)
        }
    }

    /**
     * Closes the turn. [processExit] is the CLI process's exit code; the
     * result record's own exit_code wins when present. On a replay gap
     * (or when nothing streamed) the result record's `text` is the
     * reply: any unreleased remainder of it is emitted here.
     */
    fun finish(processExit: Int): Outcome {
        // Release everything the streaming filter still holds back.
        val finalFiltered = filterThinkBlocks(rawText.toString(), final = true)
        if (finalFiltered.length > filteredText.length && finalFiltered.startsWith(filteredText.toString())) {
            val suffix = finalFiltered.substring(filteredText.length)
            filteredText.append(suffix)
            onTextDelta(suffix)
        }
        val canonical = resultText?.takeIf(String::isNotBlank)?.let { filterThinkBlocks(it, final = true) }
        if (canonical != null && (replayGap || filteredText.isEmpty())) {
            if (canonical.startsWith(filteredText.toString())) {
                val suffix = canonical.substring(filteredText.length)
                if (suffix.isNotEmpty()) {
                    filteredText.append(suffix)
                    onTextDelta(suffix)
                }
            } else if (filteredText.isEmpty()) {
                filteredText.append(canonical)
                onTextDelta(canonical)
            }
        }
        val code = resultExitCode ?: processExit
        return Outcome(
            delivered = delivered,
            completed = sawResult && code == 0,
            sessionId = sessionId,
            exitCode = code,
            responseText = filteredText.toString(),
            errorText = resultError,
        )
    }

    data class Outcome(
        val delivered: Boolean,
        val completed: Boolean,
        val sessionId: String?,
        val exitCode: Int,
        val responseText: String,
        val errorText: String?,
    )

    companion object {
        private val THINK_TAGS = listOf("think", "thinking", "reasoning", "thought", "reasoning_scratchpad")

        /**
         * Removes `<tag>…</tag>` blocks for the reasoning tag set. With
         * [final] = false, a trailing unclosed block and a trailing
         * partial tag are also removed (withheld); with [final] = true a
         * partial tag at the very end is literal text, while an unclosed
         * block still swallows the rest (it was reasoning either way).
         */
        internal fun filterThinkBlocks(text: String, final: Boolean): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val open = findTag(text, i, closing = false)
                if (open == null) {
                    // No complete open tag ahead; a trailing partial tag
                    // is withheld unless this is the final text.
                    val partial = partialTagStart(text, i, closing = false)
                    if (partial >= 0 && !final) {
                        out.append(text, i, partial)
                        return out.toString()
                    }
                    out.append(text, i, text.length)
                    return out.toString()
                }
                out.append(text, i, open.first)
                val close = findTag(text, open.second, closing = true)
                if (close == null) {
                    // Unclosed block: swallows the rest either way.
                    return out.toString()
                }
                i = close.second
            }
            return out.toString()
        }

        /** Finds the next full `<tag>` (or `</tag>`) at or after [from]; returns (start, endExclusive). */
        private fun findTag(text: String, from: Int, closing: Boolean): Pair<Int, Int>? {
            var idx = text.indexOf('<', from)
            while (idx >= 0) {
                val end = matchTag(text, idx, closing)
                if (end != null) return idx to end
                idx = text.indexOf('<', idx + 1)
            }
            return null
        }

        /** If a known tag starts at [start], returns the index just past its `>`. */
        private fun matchTag(text: String, start: Int, closing: Boolean): Int? {
            var p = start + 1
            if (closing) {
                if (p >= text.length || text[p] != '/') return null
                p++
            } else if (p < text.length && text[p] == '/') {
                return null
            }
            val nameStart = p
            while (p < text.length && (text[p].isLetterOrDigit() || text[p] == '_')) p++
            if (p == nameStart) return null
            val name = text.substring(nameStart, p).lowercase()
            if (name !in THINK_TAGS) return null
            return if (p < text.length && text[p] == '>') p + 1 else null
        }

        /**
         * If the text from [from] ends with a prefix of a known tag
         * (e.g. `<thi`), returns where that partial tag starts, else -1.
         */
        private fun partialTagStart(text: String, from: Int, closing: Boolean): Int {
            val maxLen = minOf(text.length - from, MAX_TAG_LENGTH)
            for (len in maxLen downTo 1) {
                val start = text.length - len
                if (start < from) break
                val tail = text.substring(start)
                if (tail.first() != '<') continue
                if (isTagPrefix(tail, closing)) return start
            }
            return -1
        }

        private fun isTagPrefix(tail: String, closing: Boolean): Boolean {
            for (tag in THINK_TAGS) {
                val full = if (closing) "</$tag>" else "<$tag>"
                if (full.startsWith(tail)) return true
            }
            return false
        }

        private const val MAX_TAG_LENGTH = 24 // "</reasoning_scratchpad>".length
    }
}
