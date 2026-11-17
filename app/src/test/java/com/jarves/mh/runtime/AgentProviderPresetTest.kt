package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.HERMES_PROVIDERS
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProviderPresetTest {
    @Test
    fun openCodeZenPresetIsLocked() {
        val zen = ProviderKind.OPENCODE_ZEN
        assertEquals("https://opencode.ai/zen/v1", zen.defaultBaseUrl)
        assertTrue(zen.fixedBaseUrl)
        assertTrue(zen.fixedProtocol)
        assertEquals("https://opencode.ai/zen/v1", ProviderProfile(zen).resolvedBaseUrl)
    }

    @Test
    fun storedDriftCannotOverrideFixedUrl() {
        val profile = ProviderProfile(ProviderKind.OPENCODE_ZEN, baseUrl = "https://evil.example/", model = "x")
        assertEquals("https://opencode.ai/zen/v1", profile.resolvedBaseUrl)
    }

    @Test
    fun hermesExcludesClaudeSubscription() {
        assertFalse(ProviderKind.CLAUDE in HERMES_PROVIDERS)
        assertTrue(ProviderKind.DEEPSEEK in HERMES_PROVIDERS)
        assertTrue(ProviderKind.NVIDIA_NIM in HERMES_PROVIDERS)
        assertEquals(6, HERMES_PROVIDERS.size)
        assertEquals(HERMES_PROVIDERS, providersForAgent(AgentKind.HERMES).toSet())
    }

    @Test
    fun claudeCodeOffersEveryProviderExceptOpenCodeZen() {
        val offered = providersForAgent(AgentKind.CLAUDE_CODE)
        assertTrue(ProviderKind.CLAUDE in offered)
        assertTrue(ProviderKind.DEEPSEEK in offered)
        assertFalse(ProviderKind.OPENCODE_ZEN in offered)
        assertEquals(ProviderKind.entries.size - 1, offered.size)
    }

    @Test
    fun antigravityOffersNoApiKeyProviders() {
        assertTrue(providersForAgent(AgentKind.ANTIGRAVITY).isEmpty())
    }

    @Test
    fun openCodeZenIsNotOfferedForAnyAgent() {
        AgentKind.entries.forEach { agent ->
            assertFalse(ProviderKind.OPENCODE_ZEN in providersForAgent(agent))
        }
    }

    @Test
    fun agentLineupIsHermesClaudeAntigravity() {
        assertEquals(
            listOf(AgentKind.HERMES, AgentKind.CLAUDE_CODE, AgentKind.ANTIGRAVITY),
            AgentKind.entries.toList(),
        )
    }

    @Test
    fun agentKindsAreStable() {
        assertEquals(AgentKind.HERMES, AgentKind.valueOf("HERMES"))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.valueOf("CLAUDE_CODE"))
        assertEquals(AgentKind.ANTIGRAVITY, AgentKind.fromStored("antigravity"))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored("CLAUDE_CODE"))
        assertEquals(AgentKind.HERMES, AgentKind.fromStored("hermes"))
    }

    @Test
    fun removedAgentFallsBackToHermes() {
        // DeepSeek Harness was removed; installs that persisted it must land
        // on the default agent instead of crashing or resolving to null.
        assertEquals(AgentKind.HERMES, AgentKind.fromStored("DEEPSEEK_HARNESS"))
        assertEquals(AgentKind.HERMES, AgentKind.fromStored("deepseek-harness"))
        assertEquals(AgentKind.HERMES, AgentKind.fromStored(null))
        assertEquals(AgentKind.HERMES, AgentKind.fromStored("no-such-agent"))
    }
}

class ToolTextCompactionTest {
    @Test
    fun compactsOnlyABoundedWindowOfLargeOutput() {
        val compact = compactToolText("ignored".repeat(10_000) + "\n  useful   tail ", 100, 80)

        assertTrue(compact.length <= 80)
        assertTrue(compact.endsWith("useful tail"))
    }
}
