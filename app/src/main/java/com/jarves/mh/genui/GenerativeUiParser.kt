package com.jarves.mh.genui

/**
 * Layer 2 — Response Parser.
 *
 * Finds a generative-UI payload inside an assistant message's text. The primary wire format
 * is a fenced block whose language is `generative-ui`; a bare top-level JSON object with
 * `"protocol":"generative-ui"` is accepted as a fallback. This layer only *locates* the
 * payload and splits prose from payload JSON — [GenerativeUiValidator] decides whether the
 * payload is safe to render.
 *
 * Streaming rule: a payload is only ever extracted once it is complete. An unterminated
 * fence yields [ExtractResult.Incomplete] so the UI can hide the half-arrived JSON behind a
 * placeholder instead of flashing raw JSON; partial JSON is never parsed.
 */
object GenerativeUiParser {

    sealed interface ExtractResult {
        /** A complete payload block was found; [prose] is the message text without it. */
        data class Found(val prose: String, val payloadJson: String) : ExtractResult

        /** A payload block has started but not finished (streaming); [prose] precedes it. */
        data class Incomplete(val prose: String) : ExtractResult

        data object None : ExtractResult
    }

    fun extract(text: String): ExtractResult {
        if (!text.contains(GenUiProtocol.PROTOCOL)) return ExtractResult.None
        extractFenced(text)?.let { return it }
        return extractRawJson(text)
    }

    private fun extractFenced(text: String): ExtractResult? {
        val lines = text.split('\n')
        var openIndex = -1
        var inOtherFence = false
        for (index in lines.indices) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith("```")) continue
            if (inOtherFence) {
                if (trimmed == "```") inOtherFence = false
                continue
            }
            if (trimmed.removePrefix("```").trim() == GenUiProtocol.FENCE_LANGUAGE) {
                openIndex = index
                break
            }
            inOtherFence = true
        }
        if (openIndex < 0) return null
        var closeIndex = -1
        for (index in openIndex + 1 until lines.size) {
            if (lines[index].trim() == "```") {
                closeIndex = index
                break
            }
        }
        val proseBefore = lines.subList(0, openIndex).joinToString("\n")
        if (closeIndex < 0) return ExtractResult.Incomplete(proseBefore.trim())
        val payloadJson = lines.subList(openIndex + 1, closeIndex).joinToString("\n").trim()
        val proseAfter = lines.subList(closeIndex + 1, lines.size).joinToString("\n")
        val prose = listOf(proseBefore.trim(), proseAfter.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return ExtractResult.Found(prose, payloadJson)
    }

    private fun extractRawJson(text: String): ExtractResult {
        val start = text.indexOf('{')
        if (start < 0) return ExtractResult.None
        val end = balancedObjectEnd(text, start)
        if (end < 0) {
            // Braces never balance: either a payload still streaming in, or plain prose
            // that merely mentions the protocol. Only the former is worth a placeholder.
            return if (text.contains("\"protocol\"")) {
                ExtractResult.Incomplete(text.substring(0, start).trim())
            } else {
                ExtractResult.None
            }
        }
        val candidate = text.substring(start, end + 1)
        if (!candidate.contains("\"${GenUiProtocol.PROTOCOL}\"")) return ExtractResult.None
        val prose = (text.substring(0, start).trim() + "\n\n" + text.substring(end + 1).trim())
            .trim()
        return ExtractResult.Found(prose, candidate)
    }

    /** Index of the `}` that closes the object opened at [start], or -1 if unbalanced. */
    private fun balancedObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }
        return -1
    }
}

/**
 * The render-ready split of one assistant message: prose to show as markdown plus the
 * validated payload (if any) to render underneath it.
 */
data class GenUiMessageParts(
    val prose: String,
    val payload: GenUiPayload?,
    val rawPayloadJson: String?,
    val payloadInvalid: Boolean = false,
    val payloadIncomplete: Boolean = false,
)

/** Extract + validate in one step; an invalid payload falls back to the untouched text. */
fun parseGenUiMessage(text: String): GenUiMessageParts =
    when (val result = GenerativeUiParser.extract(text)) {
        is GenerativeUiParser.ExtractResult.Found ->
            when (val validation = GenerativeUiValidator.validate(result.payloadJson)) {
                is GenerativeUiValidator.ValidationResult.Valid ->
                    GenUiMessageParts(result.prose, validation.payload, result.payloadJson)
                is GenerativeUiValidator.ValidationResult.Invalid ->
                    // Safe fallback: keep the original text, so the fence renders as an
                    // ordinary code block and nothing the agent sent is lost.
                    GenUiMessageParts(text, null, result.payloadJson, payloadInvalid = true)
            }
        is GenerativeUiParser.ExtractResult.Incomplete ->
            GenUiMessageParts(result.prose, null, null, payloadIncomplete = true)
        GenerativeUiParser.ExtractResult.None ->
            GenUiMessageParts(text, null, null)
    }

/**
 * Resolve the parts for a message that may already carry a stored payload (extracted when
 * its session completed and persisted with the transcript). Stored payloads are re-validated
 * rather than trusted; messages without one go through the lazy parse, which also covers
 * live streaming and transcripts written before this feature existed.
 */
fun resolveGenUiParts(text: String, storedPayloadJson: String?): GenUiMessageParts {
    if (storedPayloadJson == null) return parseGenUiMessage(text)
    return when (val validation = GenerativeUiValidator.validate(storedPayloadJson)) {
        is GenerativeUiValidator.ValidationResult.Valid ->
            GenUiMessageParts(text, validation.payload, storedPayloadJson)
        is GenerativeUiValidator.ValidationResult.Invalid ->
            GenUiMessageParts(text, null, storedPayloadJson, payloadInvalid = true)
    }
}
