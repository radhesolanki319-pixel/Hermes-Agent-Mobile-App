package com.jarves.mh.data

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simple Settings → "API Keys" screen's field plan under the v19
 * unified provider store: fields are keyed by PROVIDER (canonical id),
 * and when two agents are set to the same provider there is exactly ONE
 * field for it — the regression test for the OpenRouter silo bug, where
 * a Claude Code chat read an empty/stale `LLM_ROUTER` pool while the
 * user's OpenRouter key sat in the Hermes setup pool.
 *
 * Since v20 every agent — Hermes included — contributes the provider of
 * its profile in the shared profile system; the plan derives Hermes's
 * field from the Hermes profile exactly like the other agents'.
 */
class ApiKeysPlanTest {

    private val hermesOpenRouter = ProviderProfile(ProviderKind.LLM_ROUTER)
    private val claudeAnthropic = ProviderProfile(ProviderKind.ANTHROPIC)
    // What loadProvider resolves for Antigravity today: it signs in with a
    // Google account and has no API-key provider.
    private val antigravityResolved = ProviderProfile(ProviderKind.ANTHROPIC)

    private fun plan(
        hermes: ProviderProfile = hermesOpenRouter,
        hostRemote: Boolean = false,
        claude: ProviderProfile = claudeAnthropic,
        antigravity: ProviderProfile = antigravityResolved,
    ) = ApiKeysPlan.fields(hermes, hostRemote, claude, antigravity)

    private fun List<SimpleKeyField>.providerFields() =
        filter { it.kind == SimpleKeyFieldKind.PROVIDER }

    @Test
    fun sameProviderForHermesAndClaudeIsOneSharedField() {
        // THE v19 regression: Hermes on OpenRouter + Claude Code on
        // OpenRouter (LLM_ROUTER) must be a single field/pool, so the key
        // entered once serves both agents.
        val fields = plan(claude = ProviderProfile(ProviderKind.LLM_ROUTER)).providerFields()
        assertEquals(1, fields.size)
        val shared = fields.single()
        assertEquals("openrouter", shared.canonicalId)
        assertEquals("OpenRouter", shared.providerName)
        assertEquals(listOf(AgentKind.HERMES, AgentKind.CLAUDE_CODE), shared.agents)
        assertEquals(ProviderKind.LLM_ROUTER, shared.claudeKind)
        assertTrue(shared.isHermesProvider)
        // The unified pool for the provider — the same pool both bridges resolve.
        assertEquals("openrouter", shared.vaultId)
    }

    @Test
    fun sameAnthropicProviderMergesToo() {
        val fields = plan(
            hermes = ProviderProfile(ProviderKind.ANTHROPIC),
        ).providerFields()
        assertEquals(1, fields.size)
        assertEquals("anthropic", fields.single().canonicalId)
        assertEquals(
            listOf(AgentKind.HERMES, AgentKind.CLAUDE_CODE),
            fields.single().agents,
        )
    }

    @Test
    fun hermesFieldFollowsTheHermesProfileProvider() {
        val hermes = plan(
            hermes = ProviderProfile(ProviderKind.DEEPSEEK),
            claude = ProviderProfile(ProviderKind.KIMI),
        ).providerFields().first()
        assertEquals("deepseek", hermes.canonicalId)
        assertEquals("DeepSeek", hermes.providerName)
        assertEquals(listOf(AgentKind.HERMES), hermes.agents)
        assertTrue(hermes.isHermesProvider)
    }

    @Test
    fun differentProvidersGetSeparateFieldsHermesFirst() {
        val fields = plan().providerFields()
        assertEquals(2, fields.size)
        assertEquals("openrouter", fields[0].canonicalId)
        assertEquals(listOf(AgentKind.HERMES), fields[0].agents)
        assertEquals("anthropic", fields[1].canonicalId)
        assertEquals("Anthropic API", fields[1].providerName)
        assertEquals(listOf(AgentKind.CLAUDE_CODE), fields[1].agents)
        assertEquals(ProviderKind.ANTHROPIC, fields[1].claudeKind)
    }

    @Test
    fun claudeCustomMergesWithHermesCustom() {
        val fields = plan(
            hermes = ProviderProfile(ProviderKind.CUSTOM),
            claude = ProviderProfile(ProviderKind.CUSTOM),
        ).providerFields()
        assertEquals(1, fields.size)
        assertEquals("custom", fields.single().canonicalId)
        assertEquals(
            listOf(AgentKind.HERMES, AgentKind.CLAUDE_CODE),
            fields.single().agents,
        )
    }

    @Test
    fun kimiHermesProfileUsesTheKimiCodingPool() {
        val fields = plan(
            hermes = ProviderProfile(ProviderKind.KIMI),
            claude = ProviderProfile(ProviderKind.KIMI),
        ).providerFields()
        assertEquals(1, fields.size)
        assertEquals("kimi-coding", fields.single().canonicalId)
    }

    @Test
    fun claudeSubscriptionTokenIsAFieldToo() {
        val claude = plan(claude = ProviderProfile(ProviderKind.CLAUDE))
            .providerFields().first { it.canonicalId == "claude" }
        assertEquals(ProviderKind.CLAUDE, claude.claudeKind)
        assertEquals(listOf(AgentKind.CLAUDE_CODE), claude.agents)
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
            listOf("openrouter", "deepseek", "HERMES_GATEWAY"),
            fields.map { it.canonicalId },
        )
    }
}
