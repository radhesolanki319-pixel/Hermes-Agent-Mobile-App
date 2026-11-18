package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v29: the key-rotation gate. A remote Hermes turn must never rotate the
 * phone's provider key — the gateway relays the host provider's error
 * text verbatim, so a host-side failure can carry every trigger
 * substring while the failing key lives on the host. Embedded/provider
 * turns keep the pre-v29 behaviour exactly.
 */
class ApiKeyRotationPolicyTest {

    // The gateway-error passthrough shape (HermesRemoteClient composes
    // "The Hermes host reported an error: <detail>") carrying a host-side
    // FreeLLM rate-limit/quota/key failure — several trigger substrings
    // at once, none of them about a key the phone holds.
    private val hostRelayedKeyFailure =
        "The Hermes host reported an error: Rate limit exceeded — quota for this API key is exhausted (HTTP 429)"

    @Test
    fun remoteHermesTurnNeverRotatesEvenWithTriggerText() {
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.HERMES, remoteHermesTurn = true, hostRelayedKeyFailure))
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.HERMES, remoteHermesTurn = true, "authentication failed"))
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.HERMES, remoteHermesTurn = true, "http 401"))
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.HERMES, remoteHermesTurn = true, "The provider rejected the saved API key."))
    }

    @Test
    fun remoteHermesTurnNeverRotatesForOrdinaryFailuresEither() {
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.HERMES, remoteHermesTurn = true, "Could not reach Hermes on its computer."))
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.HERMES, remoteHermesTurn = true, "Stopped by user"))
    }

    @Test
    fun embeddedHermesRotatesOnKeyFailureAsBefore() {
        assertTrue(ApiKeyRotationPolicy.shouldRotate(AgentKind.HERMES, remoteHermesTurn = false, "The provider rejected the saved API key."))
        assertTrue(ApiKeyRotationPolicy.shouldRotate(AgentKind.HERMES, remoteHermesTurn = false, "OpenRouter returned http 429"))
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.HERMES, remoteHermesTurn = false, "The model did not answer."))
    }

    @Test
    fun claudeCodeRotatesOnKeyFailureAsBefore() {
        assertTrue(ApiKeyRotationPolicy.shouldRotate(AgentKind.CLAUDE_CODE, remoteHermesTurn = false, "User not found. Check the API key and provider account."))
        assertTrue(ApiKeyRotationPolicy.shouldRotate(AgentKind.CLAUDE_CODE, remoteHermesTurn = false, "authentication error: http 403"))
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.CLAUDE_CODE, remoteHermesTurn = false, "Claude Code reported an error"))
    }

    @Test
    fun antigravityNeverRotates() {
        // Pre-existing guard, folded into the policy unchanged: Google
        // sign-in has no pool key to rotate.
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.ANTIGRAVITY, remoteHermesTurn = false, "authentication failed — api key expired"))
        assertFalse(ApiKeyRotationPolicy.shouldRotate(AgentKind.ANTIGRAVITY, remoteHermesTurn = false, "Antigravity could not complete the task."))
    }

    @Test
    fun isApiKeyFailureMatchesExactlyTheTriggerSubstrings() {
        val triggers = listOf(
            "invalid api key", "authentication failed", "user not found",
            "http 401", "http 403", "http 429",
            "token expired", "quota exceeded", "rate limit reached",
        )
        triggers.forEach { reason ->
            assertTrue("'$reason' must be a key failure", ApiKeyRotationPolicy.isApiKeyFailure(reason))
        }
        // Case-insensitive, as before.
        assertTrue(ApiKeyRotationPolicy.isApiKeyFailure("API KEY rejected"))
        val nonTriggers = listOf(
            "Stopped by user",
            "The Hermes host rejected the gateway key.",
            "Could not reach Hermes on its computer. It may be waking up — wait a few seconds and try again.",
            "The connection to the Hermes host dropped before the reply finished.",
            "Claude Code reported an error",
        )
        nonTriggers.forEach { reason ->
            assertFalse("'$reason' must not be a key failure", ApiKeyRotationPolicy.isApiKeyFailure(reason))
        }
    }
}
