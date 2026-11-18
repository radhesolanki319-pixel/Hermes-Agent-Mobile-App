package com.jarves.mh.data

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.runtime.HermesProviderCatalog
import com.jarves.mh.runtime.HermesSetupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simple Settings → "API Keys" screen's field plan under the v19
 * unified provider store: fields are keyed by PROVIDER (canonical id),
 * and when two agents are set to the same provider there is exactly ONE
 * field for it — the regression test for the OpenRouter silo bug, where
 * a Claude Code chat read an empty/stale `LLM_ROUTER` pool while the
 * user's OpenRouter key sat in the Hermes setup pool.
 */
class ApiKeysPlanTest {

    private val fusionSetup = HermesSetupSettings(
        providerId = HermesProviderCatalog.DEFAULT_PROVIDER_ID,
        model = HermesProviderCatalog.DEFAULT_MODEL,
        customBaseUrl = HermesProviderCatalog.DEFAULT_CUSTOM_BASE_URL,
    )
    private val claudeAnthropic = ProviderProfile(ProviderKind.ANTHROPIC)
    // What loadProvider resolves for Antigravity today: it signs in with a
    // Google account and has no API-key provider.
    private val antigravityResolved = ProviderProfile(ProviderKind.ANTHROPIC)

    private fun plan(
        setup: HermesSetupSettings = fusionSetup,
        hostRemote: Boolean = false,
        claude: ProviderProfile = claudeAnthropic,
        antigravity: ProviderProfile = antigravityResolved,
    ) = ApiKeysPlan.fields(setup, hostRemote, claude, antigravity)

    private fun List<SimpleKeyField>.providerFields() =
        filter { it.kind == SimpleKeyFieldKind.PROVIDER }

    @Test
    fun fusionDefaultShowsOneFreeLlmKeyFieldWithTheModelSelector() {
        val hermes = plan().providerFields().first()
        assertTrue(hermes.hermesFusion)
        assertTrue(hermes.showModelSelector)
        assertEquals("FreeLLM", hermes.providerName)
        assertEquals(listOf(AgentKind.HERMES), hermes.agents)
        // The unified pool for the Custom provider — the same pool the
        // Hermes setup card and both bridges resolve.
        assertEquals("custom", hermes.canonicalId)
        assertEquals("custom", hermes.vaultId)
    }

    @Test
    fun changedHermesProviderSwapsTheFieldAndHidesTheSelector() {
        val hermes = plan(
            setup = HermesSetupSettings("anthropic", "claude-fable-5", ""),
            claude = ProviderProfile(ProviderKind.DEEPSEEK),
        ).providerFields().first()
        assertFalse(hermes.hermesFusion)
        assertFalse(hermes.showModelSelector)
        assertEquals("Anthropic", hermes.providerName)
        assertEquals("anthropic", hermes.canonicalId)
    }

    @Test
    fun customEndpointOnAnotherUrlIsNotLabelledFreeLlm() {
        val hermes = plan(
            setup = HermesSetupSettings(
                HermesProviderCatalog.CUSTOM_ID,
                "fusion",
                "https://llm.example.com/v1",
            ),
            claude = ProviderProfile(ProviderKind.DEEPSEEK),
        ).providerFields().first()
        assertFalse(hermes.hermesFusion)
        assertFalse(hermes.showModelSelector)
        assertEquals("Custom endpoint", hermes.providerName)
        assertEquals("custom", hermes.canonicalId)
    }

    @Test
    fun sameProviderForHermesAndClaudeIsOneSharedField() {
        // THE v19 regression: Hermes on OpenRouter + Claude Code on
        // OpenRouter (LLM_ROUTER) must be a single field/pool, so the key
        // entered once serves both agents.
        val fields = plan(
            setup = HermesSetupSettings("openrouter", "anthropic/claude-sonnet-4.6", ""),
            claude = ProviderProfile(ProviderKind.LLM_ROUTER),
        ).providerFields()
        assertEquals(1, fields.size)
        val shared = fields.single()
        assertEquals("openrouter", shared.canonicalId)
        assertEquals("OpenRouter", shared.providerName)
        assertEquals(listOf(AgentKind.HERMES, AgentKind.CLAUDE_CODE), shared.agents)
        assertEquals(ProviderKind.LLM_ROUTER, shared.claudeKind)
        assertTrue(shared.isHermesProvider)
    }

    @Test
    fun sameAnthropicProviderMergesToo() {
        val fields = plan(
            setup = HermesSetupSettings("anthropic", "claude-fable-5", ""),
            claude = ProviderProfile(ProviderKind.ANTHROPIC),
        ).providerFields()
        assertEquals(1, fields.size)
        assertEquals("anthropic", fields.single().canonicalId)
        assertEquals(
            listOf(AgentKind.HERMES, AgentKind.CLAUDE_CODE),
            fields.single().agents,
        )
    }

    @Test
    fun differentProvidersGetSeparateFieldsHermesFirst() {
        val fields = plan(claude = ProviderProfile(ProviderKind.DEEPSEEK)).providerFields()
        assertEquals(2, fields.size)
        assertEquals("custom", fields[0].canonicalId)
        assertEquals(listOf(AgentKind.HERMES), fields[0].agents)
        assertEquals("deepseek", fields[1].canonicalId)
        assertEquals("DeepSeek", fields[1].providerName)
        assertEquals(listOf(AgentKind.CLAUDE_CODE), fields[1].agents)
        assertEquals(ProviderKind.DEEPSEEK, fields[1].claudeKind)
        assertFalse(fields[1].showModelSelector)
    }

    @Test
    fun claudeCustomMergesWithHermesCustom() {
        val fields = plan(claude = ProviderProfile(ProviderKind.CUSTOM)).providerFields()
        assertEquals(1, fields.size)
        assertEquals("custom", fields.single().canonicalId)
        assertEquals(
            listOf(AgentKind.HERMES, AgentKind.CLAUDE_CODE),
            fields.single().agents,
        )
        // Still the fusion configuration for Hermes, so the selector stays.
        assertTrue(fields.single().showModelSelector)
    }

    @Test
    fun claudeSubscriptionTokenIsAFieldToo() {
        val claude = plan(claude = ProviderProfile(ProviderKind.CLAUDE))
            .providerFields().first { it.canonicalId == "claude" }
        assertEquals(ProviderKind.CLAUDE, claude.claudeKind)
        assertEquals(listOf(AgentKind.CLAUDE_CODE), claude.agents)
    }

    @Test
    fun kimiKindMergesWithHermesKimiCoding() {
        val fields = plan(
            setup = HermesSetupSettings("kimi-coding", "kimi-k3", ""),
            claude = ProviderProfile(ProviderKind.KIMI),
        ).providerFields()
        assertEquals(1, fields.size)
        assertEquals("kimi-coding", fields.single().canonicalId)
    }

    @Test
    fun antigravityHasNoKeyField() {
        // Google sign-in, no API-key provider — skipped gracefully.
        assertTrue(
            plan().providerFields().none { AgentKind.ANTIGRAVITY in it.agents },
        )
    }

    @Test
    fun hostKeyFieldAppearsOnlyInRemoteMode() {
        assertTrue(plan(hostRemote = false).none { it.kind == SimpleKeyFieldKind.HERMES_HOST })
        val host = plan(hostRemote = true).first { it.kind == SimpleKeyFieldKind.HERMES_HOST }
        // The gateway pool — the documented exception, NOT a provider pool.
        assertEquals("HERMES_GATEWAY", host.canonicalId)
    }

    @Test
    fun fieldOrderIsHermesProviderThenClaudeProviderThenHost() {
        val fields = plan(hostRemote = true, claude = ProviderProfile(ProviderKind.DEEPSEEK))
        assertEquals(
            listOf("custom", "deepseek", "HERMES_GATEWAY"),
            fields.map { it.canonicalId },
        )
    }
}
