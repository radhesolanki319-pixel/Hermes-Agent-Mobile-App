package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Hermes 0.19.0 quiet-transport classifier. The line streams
 * below are the real merged stdout+stderr captures from running the pinned
 * PyPI 0.19.0 release with the exact config/env the bridge writes, against
 * a local mock provider (success / 401 / unreachable / 404 / resume /
 * unknown-resume) and with the pre-fix bridge command (argparse failure).
 */
class HermesQuietOutputTest {

    private val tirith = " ⚠ tirith security scanner enabled but not available — command scanning will use pattern matching only"

    private fun outcomeOf(vararg lines: String): HermesQuietOutput.Outcome {
        val collector = HermesQuietOutput.Collector()
        lines.forEach(collector::accept)
        return collector.finish()
    }

    @Test
    fun successTurnYieldsReplyAndSessionId() {
        val outcome = outcomeOf(tirith, "MOCK REPLY", "", "session_id: 20261117_163322_85a382")
        assertEquals("MOCK REPLY", outcome.responseText)
        assertEquals("20261117_163322_85a382", outcome.sessionId)
        assertNull(outcome.errorText)
        assertNull(outcome.usageError)
    }

    @Test
    fun multiLineReplySurvivesVerbatim() {
        val outcome = outcomeOf(
            tirith,
            "Here is the code:",
            "",
            "fun main() {",
            "    println(\"hi\")",
            "}",
            "",
            "session_id: 20261117_163322_85a382",
        )
        assertEquals("Here is the code:\n\nfun main() {\n    println(\"hi\")\n}", outcome.responseText)
    }

    @Test
    fun unauthorizedTurnSurfacesHermesOwnText() {
        val outcome = outcomeOf(tirith, "HTTP 401: Invalid API key provided", "", "session_id: 20261117_163400_ab12cd")
        assertEquals(
            "HTTP 401: Invalid API key provided",
            HermesQuietOutput.failureMessage(outcome, exitCode = 1, providerError = null),
        )
        // The auth/quota pattern detector keeps its established priority.
        assertEquals(
            "The provider rejected the saved API key.",
            HermesQuietOutput.failureMessage(outcome, exitCode = 1, providerError = "The provider rejected the saved API key."),
        )
    }

    @Test
    fun unreachableProviderSurfacesRetrySummary() {
        val outcome = outcomeOf(tirith, "API call failed after 3 retries: Connection error.", "", "session_id: 20261117_163500_ab12cd")
        assertEquals(
            "API call failed after 3 retries: Connection error.",
            HermesQuietOutput.failureMessage(outcome, exitCode = 1, providerError = null),
        )
    }

    @Test
    fun unknownModelSurfaces404Text() {
        val outcome = outcomeOf(tirith, "HTTP 404: model 'claude-sonnet-5.5' not found", "", "session_id: 20261117_163600_ab12cd")
        assertEquals(
            "HTTP 404: model 'claude-sonnet-5.5' not found",
            HermesQuietOutput.failureMessage(outcome, exitCode = 1, providerError = null),
        )
    }

    @Test
    fun errorLineIsUsedWhenTurnLeftNoResponseText() {
        val outcome = outcomeOf("Error: The model provider rejected the request")
        assertEquals("The model provider rejected the request", outcome.errorText)
        assertEquals(
            "The model provider rejected the request",
            HermesQuietOutput.failureMessage(outcome, exitCode = 1, providerError = null),
        )
    }

    @Test
    fun initFailureLineIsSurfacedWhole() {
        val line = "Failed to initialize agent: Failed to initialize OpenAI client: Invalid port: ':1]'"
        val outcome = outcomeOf(line)
        assertEquals(line, outcome.errorText)
        assertEquals(line, HermesQuietOutput.failureMessage(outcome, exitCode = 1, providerError = null))
    }

    @Test
    fun unknownResumeIdReportsSessionNotFound() {
        val outcome = outcomeOf(
            tirith,
            "Session not found: 19990101_000000_deadbe",
            "Use a session ID from a previous CLI run (hermes sessions list).",
        )
        assertEquals("Session not found: 19990101_000000_deadbe", outcome.errorText)
        assertEquals("", outcome.responseText)
        assertEquals(
            "Session not found: 19990101_000000_deadbe",
            HermesQuietOutput.failureMessage(outcome, exitCode = 1, providerError = null),
        )
    }

    @Test
    fun argparseFailureNamesTheRejectedFlags() {
        // The pre-fix on-device failure, captured verbatim from 0.19.0 run
        // with the old bridge command: exit 2, stdout empty, this on stderr.
        val outcome = outcomeOf(
            "usage: hermes [-h] [--version] [-z PROMPT] [-m MODEL] [--provider PROVIDER]",
            "              [-t TOOLSETS] [--resume SESSION] [--continue [SESSION_NAME]]",
            "              {chat,model,fallback,secrets,migrate,gateway,proxy,lsp,setup,whatsapp,slack,send,login,logout,auth,status,cron,webhook,kanban,project,hooks,doctor,security,dump,debug,backup,checkpoints,import,config,pairing,skills,bundles,plugins,curator,memory,acp,tools,mcp,sessions,insights,claw,version,update,uninstall,profile,completion,dashboard,desktop,gui,logs,prompt-size}",
            "              ...",
            "hermes: error: unrecognized arguments: --query-file /root/.hermes/mh-query.txt --format stream-json",
        )
        assertEquals(
            "unrecognized arguments: --query-file /root/.hermes/mh-query.txt --format stream-json",
            outcome.usageError,
        )
        assertFalse(outcome.responseText.contains("usage:"))
        assertEquals(
            "Hermes Agent could not start: unrecognized arguments: --query-file /root/.hermes/mh-query.txt --format stream-json",
            HermesQuietOutput.failureMessage(outcome, exitCode = 2, providerError = null),
        )
    }

    @Test
    fun resumeBannerAndDiagnosticsNeverReachTheReply() {
        val outcome = outcomeOf(
            "↻ Resumed session 20261117_163322_85a382 (1 user message, 2 total messages)",
            tirith,
            "MOCK REPLY",
            "",
            "session_id: 20261117_163322_85a382",
        )
        assertEquals("MOCK REPLY", outcome.responseText)
        assertEquals("20261117_163322_85a382", outcome.sessionId)
    }

    @Test
    fun warningLinesAreDiagnosticsNotReply() {
        val outcome = outcomeOf("Warning: an optional integration is unavailable", "Real answer")
        assertEquals("Real answer", outcome.responseText)
    }

    @Test
    fun replyLineStartingWithUsageIsNotAnArgparseDump() {
        val outcome = outcomeOf(
            "Here is how the flag works:",
            "usage: run it with --fast",
            "  indented example",
        )
        assertEquals("Here is how the flag works:\nusage: run it with --fast\n  indented example", outcome.responseText)
        assertNull(outcome.usageError)
    }

    @Test
    fun tracebackTailIsTheFailureReason() {
        val outcome = outcomeOf(
            "Traceback (most recent call last):",
            "  File \"agent.py\", line 10, in <module>",
            "ValueError: boom",
        )
        assertEquals("ValueError: boom", outcome.tracebackTail)
        assertEquals("ValueError: boom", HermesQuietOutput.failureMessage(outcome, exitCode = 1, providerError = null))
    }

    @Test
    fun bareExitCodeIsOnlyTheLastResort() {
        val outcome = outcomeOf()
        assertEquals("", outcome.responseText)
        assertEquals(
            "Hermes Agent stopped with exit code 2",
            HermesQuietOutput.failureMessage(outcome, exitCode = 2, providerError = null),
        )
    }

    // -- Reasoning leak (v22) -------------------------------------------------
    // The user's real capture shape: a plain "hi" answered with the model's
    // reasoning framed in Hermes CLI's reasoning box (paragraphs repeated,
    // as streamed + post-turn display both render it) before the answer.
    // Only the answer may reach the stored message.

    @Test
    fun framedReasoningBoxIsDroppedAnswerKept() {
        val outcome = outcomeOf(
            tirith,
            "┌─ Reasoning ─────────────────────────────┐",
            "The user just said hi. This is a simple greeting.",
            "I should respond warmly and briefly.",
            "",
            "The user just said hi. This is a simple greeting.",
            "I should respond warmly and briefly.",
            "└─────────────────────────────────────────┘",
            "Hi there! How can I help you today?",
            "",
            "session_id: 20261118_213100_ab12cd",
        )
        assertEquals("Hi there! How can I help you today?", outcome.responseText)
        assertEquals("20261118_213100_ab12cd", outcome.sessionId)
    }

    @Test
    fun ansiWrappedReasoningBoxIsDropped() {
        val dim = "\u001B[2m"
        val rst = "\u001B[0m"
        val outcome = outcomeOf(
            "$dim┌─ Reasoning ───┐$rst",
            dim + "Thinking out loud." + rst,
            "$dim└───┘$rst",
            "The answer.",
            "",
            "session_id: 20261118_213200_ab12cd",
        )
        assertEquals("The answer.", outcome.responseText)
    }

    @Test
    fun thinkTagBlockIsDroppedAcrossLines() {
        val outcome = outcomeOf(
            "<think>",
            "Let me think about this greeting.",
            "</think>",
            "Hello! Nice to meet you.",
            "",
            "session_id: 20261118_213300_ab12cd",
        )
        assertEquals("Hello! Nice to meet you.", outcome.responseText)
    }

    @Test
    fun inlineThinkSegmentIsRemovedButAnswerTextKept() {
        val outcome = outcomeOf(
            "<think>hmm</think>The answer stands.",
            "",
            "session_id: 20261118_213400_ab12cd",
        )
        assertEquals("The answer stands.", outcome.responseText)
    }

    @Test
    fun reasoningBoxBetweenAnswerPartsKeepsBothParts() {
        val outcome = outcomeOf(
            "First part.",
            "┌─ Reasoning ───┐",
            "middle thoughts",
            "└───┘",
            "Second part.",
            "",
            "session_id: 20261118_213500_ab12cd",
        )
        assertEquals("First part.\nSecond part.", outcome.responseText)
    }

    @Test
    fun utf8HelpersRespectByteBudgets() {
        assertEquals(6, utf8ByteCount("héllo"))
        assertEquals("héllo", truncateToUtf8Bytes("héllo", 6))
        assertEquals("hé", truncateToUtf8Bytes("héllo", 3))
        // Cap inside the two-byte é: back off rather than split the sequence.
        assertEquals("h", truncateToUtf8Bytes("héllo", 2))
        assertEquals("", truncateToUtf8Bytes("héllo", 0))
        assertTrue(truncateToUtf8Bytes("héllo", 4).toByteArray(Charsets.UTF_8).size <= 4)
    }
}
