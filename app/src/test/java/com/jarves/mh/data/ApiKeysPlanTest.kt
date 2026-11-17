package com.jarves.mh.data

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.runtime.HermesProviderCatalog
import com.jarves.mh.runtime.HermesSetupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simple Settings → "API Keys" screen's field plan: which fields show
 * for the current configuration, and — the safety property — that every
 * field writes to the SAME ApiKeyVault pool the advanced surfaces and the
 * agent bridges already use.
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

    @Test
    fun fusionDefaultShowsOneFreeLlmKeyFieldWithTheModelSelector() {
        val fields = plan()
        val hermes = fields.first { it.kind == SimpleKeyFieldKind.HERMES }
        assertTrue(hermes.hermesFusion)
        assertTrue(hermes.showModelSelector)
        assertEquals("FreeLLM", hermes.providerName)
        // The pool the Hermes setup card and the bridge already use.
        assertEquals("HERMES_SETUP", hermes.vaultId)
    }

    @Test
    fun changedHermesProviderSwapsTheFieldAndHidesTheSelector() {
        val fields = plan(
            setup = HermesSetupSettings("anthropic", "claude-fable-5", ""),
        )
        val hermes = fields.first { it.kind == SimpleKeyFieldKind.HERMES }
        assertFalse(hermes.hermesFusion)
        assertFalse(hermes.showModelSelector)
        assertEquals("Anthropic", hermes.providerName)
        // Still the same single Hermes key pool — the provider has only one key.
        assertEquals("HERMES_SETUP", hermes.vaultId)
    }

    @Test
    fun customEndpointOnAnotherUrlIsNotLabelledFreeLlm() {
        val fields = plan(
            setup = HermesSetupSettings(
                HermesProviderCatalog.CUSTOM_ID,
                "fusion",
                "https://llm.example.com/v1",
            ),
        )
        val hermes = fields.first { it.kind == SimpleKeyFieldKind.HERMES }
        assertFalse(hermes.hermesFusion)
        assertFalse(hermes.showModelSelector)
        assertEquals("Custom endpoint", hermes.providerName)
    }

    @Test
    fun claudeCodeFieldUsesItsCurrentProvidersVaultPool() {
        val fields = plan()
        val claude = fields.first { it.kind == SimpleKeyFieldKind.CLAUDE_CODE }
        assertEquals(ProviderKind.ANTHROPIC, claude.providerKind)
        // The pool the Claude bridge reads: vault.get(profile.kind.name).
        assertEquals("ANTHROPIC", claude.vaultId)

        val custom = plan(claude = ProviderProfile(ProviderKind.CUSTOM))
            .first { it.kind == SimpleKeyFieldKind.CLAUDE_CODE }
        assertEquals("CUSTOM", custom.vaultId)
    }

    @Test
    fun claudeSubscriptionTokenIsAFieldToo() {
        val claude = plan(claude = ProviderProfile(ProviderKind.CLAUDE))
            .first { it.kind == SimpleKeyFieldKind.CLAUDE_CODE }
        assertEquals("CLAUDE", claude.vaultId)
    }

    @Test
    fun antigravityHasNoKeyField() {
        // Google sign-in, no API-key provider — skipped gracefully.
        assertTrue(plan().none { it.kind == SimpleKeyFieldKind.ANTIGRAVITY })
    }

    @Test
    fun hostKeyFieldAppearsOnlyInRemoteMode() {
        assertTrue(plan(hostRemote = false).none { it.kind == SimpleKeyFieldKind.HERMES_HOST })
        val host = plan(hostRemote = true).first { it.kind == SimpleKeyFieldKind.HERMES_HOST }
        // The pool the Hermes host card and the remote client already use.
        assertEquals("HERMES_GATEWAY", host.vaultId)
    }

    @Test
    fun fieldOrderIsHermesThenAgentsThenHost() {
        assertEquals(
            listOf(
                SimpleKeyFieldKind.HERMES,
                SimpleKeyFieldKind.CLAUDE_CODE,
                SimpleKeyFieldKind.HERMES_HOST,
            ),
            plan(hostRemote = true).map { it.kind },
        )
    }
}
