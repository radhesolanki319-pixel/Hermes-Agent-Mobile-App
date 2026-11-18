package com.jarves.mh.data

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.providersForAgent
import com.jarves.mh.runtime.HERMES_GATEWAY_VAULT_ID
import com.jarves.mh.runtime.HermesProviderCatalog
import com.jarves.mh.runtime.HermesSetupSettings

/**
 * Which key fields the simple Settings → "API Keys" screen shows.
 *
 * Since v19 the app has ONE key store per provider (see [ProviderKeys]):
 * an [ApiKeyVault] pool named by the provider's canonical id, shared by
 * every agent. This plan is therefore provider-keyed, not agent-keyed:
 * it lists the providers the configured agents are currently set to —
 * Hermes's chosen provider and Claude Code's profile provider — and when
 * both point at the SAME provider (e.g. both on OpenRouter) it produces
 * ONE field for that provider, not a "Hermes key" and a separate
 * "Claude Code key". A key entered in that field is the key both agents
 * use, and it is the same key the advanced surfaces (Hermes setup card,
 * profile provider card) show.
 *
 * The Hermes host gateway key is NOT a provider key — it authenticates
 * to the user's own Hermes host computer — so it keeps its own field
 * (remote-host mode only) and its own pool.
 *
 * This file is the pure decision logic — which fields exist for the
 * current configuration — so it can be unit-tested without Android.
 */

/** The two field shapes the simple screen knows how to show. */
enum class SimpleKeyFieldKind { PROVIDER, HERMES_HOST }

/**
 * One field of the simple screen.
 *
 * @property canonicalId the unified store's pool id for this provider —
 * the [ApiKeyVault] pool every surface reads and writes. For the host
 * field this is the gateway pool id instead (the documented exception).
 * @property providerName plain display name of the provider the key
 * belongs to — the field's heading and label always use it, for every
 * provider alike (e.g. "FreeLLM", "Anthropic", "OpenRouter").
 * @property agents the agents whose current provider this field is —
 * used only for the hint text (a shared field says so).
 * @property claudeKind Claude Code's profile kind when Claude Code is
 * one of [agents]; the UI needs it for the Claude subscription token's
 * special label.
 * @property hermesFusion true when Hermes is on this field's provider
 * in the FreeLLM fusion configuration (Custom provider on the FreeLLM
 * endpoint). Drives ONLY the Fusion/Auto model selector's visibility.
 */
data class SimpleKeyField(
    val kind: SimpleKeyFieldKind,
    val canonicalId: String,
    val providerName: String,
    val agents: List<AgentKind> = emptyList(),
    val claudeKind: ProviderKind? = null,
    val hermesFusion: Boolean = false,
) {
    /** The vault pool this field reads and writes — the canonical provider pool. */
    val vaultId: String get() = canonicalId

    /** True when Hermes's current provider is this field's provider. */
    val isHermesProvider: Boolean get() = AgentKind.HERMES in agents

    /** The Fusion/Auto model selector belongs under Hermes's provider field, only in the fusion configuration. */
    val showModelSelector: Boolean
        get() = kind == SimpleKeyFieldKind.PROVIDER && isHermesProvider && hermesFusion
}

object ApiKeysPlan {

    /** Display name for Hermes's key in the default fusion configuration. */
    const val FREELLM_NAME = "FreeLLM"

    /**
     * The fields to show, in display order: Hermes's provider first,
     * then Claude Code's provider (merged into the Hermes field when it
     * is the same provider), then the Hermes host key.
     *
     * - Hermes is always present, and the field always follows the
     *   provider currently chosen in the advanced Hermes setup (the
     *   fusion configuration included — its provider name is "FreeLLM").
     *   The simple screen never steers the stored choice (v17).
     * - Claude Code / Antigravity join only when the agent's current
     *   profile provider is one that agent actually takes an API key
     *   (or, for Claude's subscription kind, its setup token) for — the
     *   same set the Agent screen's provider card offers. Antigravity
     *   signs in with a Google account and offers no key provider today,
     *   so it is skipped until that changes.
     * - The Hermes host gateway key appears only in remote-host mode.
     */
    fun fields(
        hermesSetup: HermesSetupSettings,
        hermesHostRemote: Boolean,
        claudeProfile: ProviderProfile,
        antigravityProfile: ProviderProfile,
    ): List<SimpleKeyField> {
        val result = mutableListOf<SimpleKeyField>()

        val fusion = hermesSetup.isCustom() &&
            hermesSetup.customBaseUrl.trim() == HermesProviderCatalog.DEFAULT_CUSTOM_BASE_URL
        result += SimpleKeyField(
            kind = SimpleKeyFieldKind.PROVIDER,
            canonicalId = ProviderKeys.canonicalForHermes(hermesSetup.providerId),
            providerName = when {
                fusion -> FREELLM_NAME
                else -> hermesSetup.providerDef()?.displayName
                    ?: HermesProviderCatalog.CUSTOM_DEF.displayName
            },
            agents = listOf(AgentKind.HERMES),
            hermesFusion = fusion,
        )

        if (claudeProfile.kind in providersForAgent(AgentKind.CLAUDE_CODE)) {
            mergeProfileProvider(result, AgentKind.CLAUDE_CODE, claudeProfile)
        }
        if (antigravityProfile.kind in providersForAgent(AgentKind.ANTIGRAVITY)) {
            mergeProfileProvider(result, AgentKind.ANTIGRAVITY, antigravityProfile)
        }

        if (hermesHostRemote) {
            result += SimpleKeyField(
                kind = SimpleKeyFieldKind.HERMES_HOST,
                canonicalId = HERMES_GATEWAY_VAULT_ID,
                providerName = "Hermes host",
                agents = listOf(AgentKind.HERMES),
            )
        }
        return result
    }

    /**
     * Adds [profile]'s provider as a field, or — when a field for the
     * same canonical provider already exists — merges [agent] into that
     * field instead of showing the provider's key twice.
     */
    private fun mergeProfileProvider(
        result: MutableList<SimpleKeyField>,
        agent: AgentKind,
        profile: ProviderProfile,
    ) {
        val canonicalId = ProviderKeys.canonicalForKind(profile.kind)
        val existing = result.indexOfFirst {
            it.kind == SimpleKeyFieldKind.PROVIDER && it.canonicalId == canonicalId
        }
        if (existing >= 0) {
            val field = result[existing]
            result[existing] = field.copy(
                agents = field.agents + agent,
                claudeKind = if (agent == AgentKind.CLAUDE_CODE) profile.kind else field.claudeKind,
            )
        } else {
            result += SimpleKeyField(
                kind = SimpleKeyFieldKind.PROVIDER,
                canonicalId = canonicalId,
                providerName = profile.kind.title,
                agents = listOf(agent),
                claudeKind = if (agent == AgentKind.CLAUDE_CODE) profile.kind else null,
            )
        }
    }
}
