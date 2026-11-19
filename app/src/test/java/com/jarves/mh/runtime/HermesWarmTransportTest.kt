package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v32 warm transport, pure parts: the SSE parser (patterns restored
 * from the deleted Phase 4 remote client, which decoded the identical
 * wire protocol against the same server code), the stream assembler's
 * equivalence with the one-shot path's [HermesQuietOutput.Collector]
 * classification, the CLI fallback policy, session planning, the
 * server key/fingerprint rules, the stream-end verdicts and the
 * request-body shape.
 */
class HermesWarmTransportTest {

    // -- SSE parsing ---------------------------------------------------

    /** Feeds a raw SSE stream through the parser line by line, as the client does. */
    private fun parseAll(stream: String): List<HermesLocalEvent> {
        val parser = HermesLocalSseParser()
        val events = mutableListOf<HermesLocalEvent>()
        stream.split("\n").forEach { line -> events += parser.feed(line) }
        events += parser.endOfStream()
        return events
    }

    @Test
    fun sseParserEmitsContentDeltasAndStreamEnd() {
        val events = parseAll(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"world\"}}]}\n\n" +
                "data: [DONE]\n\n",
        )
        assertEquals(
            listOf(
                HermesLocalEvent.TextDelta("Hello "),
                HermesLocalEvent.TextDelta("world"),
                HermesLocalEvent.StreamEnded,
            ),
            events,
        )
    }

    @Test
    fun sseParserHoldsAFrameUntilItsBlankLine() {
        val parser = HermesLocalSseParser()
        assertTrue(parser.feed("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}").isEmpty())
        assertEquals(
            listOf(HermesLocalEvent.TextDelta("x")),
            parser.feed(""),
        )
    }

    @Test
    fun sseParserFlushesATrailingFrameAtEndOfStream() {
        val events = parseAll("data: {\"choices\":[{\"delta\":{\"content\":\"tail\"}}]}")
        assertEquals(listOf(HermesLocalEvent.TextDelta("tail")), events)
    }

    @Test
    fun sseParserRoutesReasoningAndNamedFrames() {
        val events = parseAll(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm\"}}]}\n\n" +
                "event: hermes.tool.progress\n" +
                "data: {\"tool\":\"terminal\",\"label\":\"Running ls\",\"status\":\"running\",\"toolCallId\":\"t1\"}\n\n" +
                "event: hermes.status\n" +
                "data: {\"kind\":\"thinking\",\"text\":\"warming up\"}\n\n" +
                "event: approval.request\n" +
                "data: {}\n\n",
        )
        assertEquals(
            listOf(
                HermesLocalEvent.ReasoningDelta("hmm"),
                HermesLocalEvent.ToolProgress("terminal", "Running ls", "running", "t1"),
                HermesLocalEvent.Status("thinking", "warming up"),
                HermesLocalEvent.ApprovalRequested,
            ),
            events,
        )
    }

    @Test
    fun sseParserReadsTheTerminalChunksFinishReasonAndError() {
        val events = parseAll(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"error\"}],\"error\":{\"message\":\"provider said no\"}}\n\n",
        )
        assertEquals(
            listOf(
                HermesLocalEvent.Finished("stop", null),
                HermesLocalEvent.Finished("error", "provider said no"),
            ),
            events,
        )
    }

    @Test
    fun sseParserDropsMalformedFramesWithoutDying() {
        val events = parseAll(
            "data: {not json}\n\n" +
                ": a comment line\n\n" +
                "event: ping\ndata: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n",
        )
        assertEquals(listOf(HermesLocalEvent.TextDelta("ok")), events)
    }

    // -- Stream assembly == one-shot classification ---------------------

    /** Feeds [deltas] through the assembler and the same text, line by line, through a Collector. */
    private fun assertAssemblerMatchesCollector(deltas: List<String>) {
        val assembler = HermesStreamAssembler()
        val emitted = StringBuilder()
        deltas.forEach { delta -> emitted.append(assembler.feed(delta)) }
        emitted.append(assembler.finish())
        val streamedOutcome = assembler.outcome

        val collector = HermesQuietOutput.Collector()
        deltas.joinToString("").split("\n").forEach { collector.accept(it) }
        val cliOutcome = collector.finish()

        assertEquals(cliOutcome.responseText, streamedOutcome.responseText)
        assertEquals(cliOutcome.sessionId, streamedOutcome.sessionId)
        // The text the user watched arrive is exactly the stored text.
        assertFalse(assembler.diverged)
        assertEquals(streamedOutcome.responseText, emitted.toString())
    }

    @Test
    fun assemblerPlainReplyAcrossDeltas() {
        assertAssemblerMatchesCollector(listOf("Hello ", "world", "!"))
    }

    @Test
    fun assemblerThinkBlockSplitAcrossDeltas() {
        assertAssemblerMatchesCollector(listOf("<thi", "nk>secret plans</thi", "nk>The answer."))
    }

    @Test
    fun assemblerReasoningBoxIsNeverEmitted() {
        val assembler = HermesStreamAssembler()
        val emitted = StringBuilder()
        listOf("┌─ Reasoning ───┐\n", "│ thinking out loud\n", "└───┘\n", "Visible answer.")
            .forEach { emitted.append(assembler.feed(it)) }
        emitted.append(assembler.finish())
        assertEquals("Visible answer.", assembler.outcome.responseText)
        assertEquals("Visible answer.", emitted.toString())
        assertFalse(emitted.contains("thinking"))
    }

    @Test
    fun assemblerCollapsesBlankLineRunsLikeTheCollector() {
        assertAssemblerMatchesCollector(listOf("First\n\n\n\n", "Second"))
    }

    @Test
    fun assemblerDropsDiagnosticLinesAndReadsTheSessionTrailer() {
        assertAssemblerMatchesCollector(
            listOf("⚠ tirith security scanner is not installed\n", "Real reply.\n", "session_id: abc-123"),
        )
    }

    @Test
    fun assemblerKeepsMultiLineRepliesIntact() {
        assertAssemblerMatchesCollector(listOf("Line one\nLine two\n", "Line three"))
    }

    @Test
    fun assemblerStreamsThePendingLineBeforeItsNewline() {
        // The core streaming property: a one-line reply (no newline in
        // the content at all) is visible token by token, not only when
        // the turn ends.
        val assembler = HermesStreamAssembler()
        // The canonical text is trimmed, so the first suffix is "Hello"
        // (no trailing space) and the second carries the space.
        assertEquals("Hello", assembler.feed("Hello "))
        assertEquals(" world", assembler.feed("world"))
        assertEquals("", assembler.finish())
        assertEquals("Hello world", assembler.outcome.responseText)
    }

    // -- Fallback policy -------------------------------------------------

    @Test
    fun fallbackOnlyReplaysTurnsThatDeliveredNothing() {
        assertTrue(HermesWarmFallback.shouldUseCli(HermesWarmTurnOutcome.Unavailable))
        assertTrue(
            HermesWarmFallback.shouldUseCli(
                HermesWarmTurnOutcome.Failed("boom", sessionId = null, deliveredText = false),
            ),
        )
        assertFalse(
            HermesWarmFallback.shouldUseCli(
                HermesWarmTurnOutcome.Failed("boom", sessionId = "s1", deliveredText = true),
            ),
        )
        assertFalse(
            HermesWarmFallback.shouldUseCli(
                HermesWarmTurnOutcome.Completed(sessionId = "s1", text = "hi"),
            ),
        )
        assertFalse(HermesWarmFallback.shouldUseCli(HermesWarmTurnOutcome.Stopped))
    }

    // -- Session planning --------------------------------------------------

    @Test
    fun plannerResumesOnlySessionsTheServerStillHas() {
        assertEquals(
            HermesSessionPlan.Resume("s1"),
            HermesSessionPlanner.plan("s1", HermesSessionProbe.EXISTS),
        )
        assertEquals(HermesSessionPlan.Fresh, HermesSessionPlanner.plan("s1", HermesSessionProbe.MISSING))
        // A probe that could not decide must not gamble on a resume the
        // server would silently start empty.
        assertEquals(HermesSessionPlan.Fresh, HermesSessionPlanner.plan("s1", HermesSessionProbe.UNKNOWN))
        assertEquals(HermesSessionPlan.Fresh, HermesSessionPlanner.plan(null, HermesSessionProbe.EXISTS))
        assertEquals(HermesSessionPlan.Fresh, HermesSessionPlanner.plan("  ", HermesSessionProbe.EXISTS))
    }

    // -- Server key + fingerprint -------------------------------------------

    private val route = HermesRoute(
        provider = "openrouter",
        keyEnv = "OPENROUTER_API_KEY",
        baseUrl = "https://openrouter.ai/api/v1",
    )

    @Test
    fun serverKeyIsUsableAndGeneratedKeysAreLongEnough() {
        assertTrue(HermesWarmServerKey.isUsable("0123456789abcdef"))
        assertFalse(HermesWarmServerKey.isUsable("short"))
        assertFalse(HermesWarmServerKey.isUsable("has whitespace init"))
        val generated = HermesWarmServerKey.generate()
        assertTrue(HermesWarmServerKey.isUsable(generated))
        assertTrue(generated.length >= 32)
    }

    @Test
    fun fingerprintTracksLaunchInputsButNotTheModel() {
        val base = HermesWarmFingerprint.of("/workspace/p", route, "secret-key")
        assertEquals(base, HermesWarmFingerprint.of("/workspace/p", route, "secret-key"))
        // A rotated key, another workspace or another route must each
        // restart the server (it resolves the key and its terminal
        // working directory from its own process state at startup).
        assertFalse(base == HermesWarmFingerprint.of("/workspace/p", route, "other-key"))
        assertFalse(base == HermesWarmFingerprint.of("/workspace/q", route, "secret-key"))
        assertFalse(base == HermesWarmFingerprint.of("/workspace/p", route.copy(provider = "anthropic"), "secret-key"))
        assertFalse(base == HermesWarmFingerprint.of("/workspace/p", route.copy(baseUrl = "https://x.example/v1"), "secret-key"))
        assertFalse(base == HermesWarmFingerprint.of("/workspace/p", route.copy(custom = true), "secret-key"))
        // The secret itself never appears in the fingerprint.
        assertFalse(base.secretDigest.contains("secret-key"))
        assertEquals(64, base.secretDigest.length)
    }

    // -- Stream-end verdicts --------------------------------------------------

    @Test
    fun verdictsMapStreamEndingsToOutcomes() {
        assertEquals(
            HermesWarmStreamEnd.Verdict.Completed,
            HermesWarmStreamEnd.verdict(finishReason = "stop", finishError = null, sawStreamEnd = true, readFailed = false),
        )
        assertEquals(
            HermesWarmStreamEnd.Verdict.Completed,
            HermesWarmStreamEnd.verdict(finishReason = "length", finishError = null, sawStreamEnd = true, readFailed = false),
        )
        assertEquals(
            HermesWarmStreamEnd.Verdict.Completed,
            HermesWarmStreamEnd.verdict(finishReason = null, finishError = null, sawStreamEnd = true, readFailed = false),
        )
        assertEquals(
            HermesWarmStreamEnd.Verdict.Failed("provider said no"),
            HermesWarmStreamEnd.verdict(finishReason = "stop", finishError = "provider said no", sawStreamEnd = true, readFailed = false),
        )
        assertEquals(
            HermesWarmStreamEnd.Verdict.Failed("Hermes ended the turn early (tool_calls)."),
            HermesWarmStreamEnd.verdict(finishReason = "tool_calls", finishError = null, sawStreamEnd = true, readFailed = false),
        )
        assertEquals(
            HermesWarmStreamEnd.Verdict.Dropped,
            HermesWarmStreamEnd.verdict(finishReason = null, finishError = null, sawStreamEnd = false, readFailed = false),
        )
        assertEquals(
            HermesWarmStreamEnd.Verdict.Dropped,
            HermesWarmStreamEnd.verdict(finishReason = null, finishError = null, sawStreamEnd = true, readFailed = true),
        )
    }

    // -- Request shape + error bodies ------------------------------------------

    @Test
    fun chatBodyStreamsAndOmitsAnUnsetModel() {
        val unset = HermesLocalRequests.chatCompletionBody(model = "", prompt = "hi")
        assertTrue(unset.getBoolean("stream"))
        assertFalse(unset.has("model"))
        val messages = unset.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("hi", messages.getJSONObject(0).getString("content"))

        val set = HermesLocalRequests.chatCompletionBody(model = "fusion", prompt = "hi")
        assertEquals("fusion", set.getString("model"))
    }

    @Test
    fun errorMessageComesFromTheServersErrorBody() {
        assertEquals(
            "Invalid API key",
            HermesLocalErrors.parseErrorMessage("{\"error\":{\"message\":\"Invalid API key\"}}"),
        )
        assertNull(HermesLocalErrors.parseErrorMessage("not json"))
        assertNull(HermesLocalErrors.parseErrorMessage(""))
    }

    @Test
    fun endpointUrlsAreTheLoopbackApiServer() {
        assertEquals("http://127.0.0.1:8642/v1/chat/completions", HermesWarmEndpoint.CHAT_COMPLETIONS_URL)
        assertEquals("http://127.0.0.1:8642/v1/models", HermesWarmEndpoint.MODELS_URL)
        assertEquals("http://127.0.0.1:8642/api/sessions/s%201", HermesWarmEndpoint.sessionUrl("s 1"))
    }
}
