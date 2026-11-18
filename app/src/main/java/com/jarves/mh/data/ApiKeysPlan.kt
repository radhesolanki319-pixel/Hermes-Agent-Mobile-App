package com.jarves.mh.data

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.providersForAgent
import com.jarves.mh.runtime.HERMES_GATEWAY_VAULT_ID
import com.jarves.mh.runtime.HERMES_SETUP_VAULT_ID
import com.jarves.mh.runtime.HermesProviderCatalog
import com.jarves.mh.runtime.HermesSetupSettings

/**
 * Which key fields the simple Settings → "API Keys" screen shows, and which
 * existing vault pool each one reads and writes.
 *
 * The simple screen is only a front door: every field maps to the SAME
 * [ApiKeyVault] pool the advanced surfaces already use (the Hermes setup
 * card, the Agent provider card's key pools, the Hermes host card), so a
 * key entered in either place is the same key and every existing read path
 * (agent bridges, the remote-host client, profile loading) keeps working
 * unchanged. This file is the pure decision logic — which fields exist for
 * the current configuration — so it can be unit-tested without Android.
 */

/** The four key fields the simple screen knows how to show. */
enum class SimpleKeyFieldKind { HERMES, CLAUDE_CODE, ANTIGRAVITY, HERMES_HOST }

/**
 * One field of the simple screen.
 *
 * @property vaultId the [ApiKeyVault] pool the field's key lives in — always
 * an id the existing code already uses, never a new one.
 * @property providerKind the profile provider the field belongs to (profile
 * fields only; null for the Hermes-native and host fields).
 * @property providerName plain display name of the provider the key belongs
 * to — the field's label and hint always use it, for every provider
 * alike (e.g. "FreeLLM", "Anthropic", "OpenRouter").
 * @property hermesFusion true when the Hermes field is in the FreeLLM
 * fusion configuration (Custom provider on the FreeLLM endpoint). Since
 * v17 this drives ONLY the Fusion/Auto model selector's visibility: the
 * label and hint are the current provider's name in every configuration.
 */
data class SimpleKeyField(
    val kind: SimpleKeyFieldKind,
    val vaultId: String,
    val providerKind: ProviderKind?,
    val providerName: String,
    val hermesFusion: Boolean = false,
) {
    /** The Fusion/Auto model selector belongs under the Hermes field, and only in the fusion configuration. */
    val showModelSelector: Boolean
        get() = kind == SimpleKeyFieldKind.HERMES && hermesFusion
}

object ApiKeysPlan {

    /** Display name for the Hermes key in the default fusion configuration. */
    const val FREELLM_NAME = "FreeLLM"

    /**
     * The fields to show, in display order: Hermes first, then the other
     * agents' active-provider keys, then the Hermes host key.
     *
     * - Hermes is always present, and the field always follows the
     *   provider currently chosen in the advanced Hermes setup (the
     *   fusion configuration included — its provider name is "FreeLLM").
     *   The simple screen never steers the stored choice (v17).
     * - Claude Code / Antigravity appear only when the agent's current
     *   profile provider is one of the providers that agent actually takes
     *   an API key (or, for Claude's subscription kind, its setup token)
     *   for — the same set the Agent screen's provider card offers.
     *   Antigravity signs in with a Google account and offers no key
     *   provider today, so it is skipped until that changes.
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
            kind = SimpleKeyFieldKind.HERMES,
            vaultId = HERMES_SETUP_VAULT_ID,
            providerKind = null,
            providerName = when {
                fusion -> FREELLM_NAME
                else -> hermesSetup.providerDef()?.displayName
                    ?: HermesProviderCatalog.CUSTOM_DEF.displayName
            },
            hermesFusion = fusion,
        )

        if (claudeProfile.kind in providersForAgent(AgentKind.CLAUDE_CODE)) {
            result += profileField(SimpleKeyFieldKind.CLAUDE_CODE, claudeProfile)
        }
        if (antigravityProfile.kind in providersForAgent(AgentKind.ANTIGRAVITY)) {
            result += profileField(SimpleKeyFieldKind.ANTIGRAVITY, antigravityProfile)
        }

        if (hermesHostRemote) {
            result += SimpleKeyField(
                kind = SimpleKeyFieldKind.HERMES_HOST,
                vaultId = HERMES_GATEWAY_VAULT_ID,
                providerKind = null,
                providerName = "Hermes host",
            )
        }
        return result
    }

    /** A profile agent's field: the vault pool is the provider kind's name, exactly as the bridges read it. */
    private fun profileField(kind: SimpleKeyFieldKind, profile: ProviderProfile): SimpleKeyField =
        SimpleKeyField(
            kind = kind,
            vaultId = profile.kind.name,
            providerKind = profile.kind,
            providerName = profile.kind.title,
        )
}
