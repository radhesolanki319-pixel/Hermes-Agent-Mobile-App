package com.jarves.mh.data

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.runtime.HERMES_SETUP_VAULT_ID

/**
 * The unified provider-key store (v19).
 *
 * Before v19, API keys lived in per-agent silos inside [ApiKeyVault]: the
 * Hermes setup kept ONE key in the `HERMES_SETUP` pool for whichever
 * provider was selected there, while Claude Code's profiles kept separate
 * pools named by [ProviderKind.name] (`LLM_ROUTER`, `ANTHROPIC`, …). The
 * same provider — OpenRouter above all — therefore had two unrelated
 * stores: a key entered for Hermes never reached a Claude Code chat on
 * OpenRouter, which then failed with "The provider rejected the saved API
 * key" (it was reading a stale key from its own pool) or "No API key is
 * saved for OpenRouter" (its pool was empty) while the user's real key sat
 * in the other silo.
 *
 * From v19 there is exactly ONE store per provider: an [ApiKeyVault] pool
 * whose id is the provider's canonical id (see [ProviderKeys]). Every
 * consumer — the Hermes bridge, the Claude Code bridge, the profile
 * provider card (which the Hermes tab shares with Claude Code since
 * v20), and the simple Settings → API Keys screen — reads and writes
 * that one pool, so a key entered anywhere is the key everywhere.
 *
 * The one deliberate exception is the Hermes remote-host gateway key
 * (`HERMES_GATEWAY` pool): it authenticates to the user's own Hermes host
 * computer, it is not a provider key, and it stays in its own pool.
 */

/**
 * The minimal vault surface the key migration runs against — exactly the
 * multi-key operations [ApiKeyVault] already exposes, abstracted so the
 * migration logic is unit-testable without Android.
 */
interface KeyPoolStore {
    fun credentials(poolId: String): List<ApiKeyCredential>
    fun add(poolId: String, name: String, secret: String): ApiKeyInfo
    fun activate(poolId: String, keyId: String): Boolean
}

/** Canonical provider ids and the mapping from each agent's own catalogue onto them. */
object ProviderKeys {

    /**
     * The canonical id for a profile-system [ProviderKind]. Hermes's
     * catalogue ids are the canonical form, so kinds that name a Hermes
     * provider map onto that provider's Hermes id:
     *
     * | ProviderKind    | canonical id     | Hermes catalogue id |
     * |-----------------|------------------|---------------------|
     * | ANTHROPIC       | `anthropic`      | `anthropic`         |
     * | LLM_ROUTER      | `openrouter`     | `openrouter`        |
     * | DEEPSEEK        | `deepseek`       | `deepseek`          |
     * | KIMI            | `kimi-coding`    | `kimi-coding`       |
     * | OPENCODE_ZEN    | `opencode-zen`   | `opencode-zen`      |
     * | NVIDIA_NIM      | `nvidia`         | `nvidia`            |
     * | CUSTOM          | `custom`         | `custom`            |
     * | CLAUDE          | `claude`         | — (Claude Code only)|
     *
     * `CLAUDE` is the Claude subscription setup token: no Hermes
     * equivalent exists, but it still lives in the same unified store
     * under its own canonical id.
     */
    fun canonicalForKind(kind: ProviderKind): String = when (kind) {
        ProviderKind.CLAUDE -> "claude"
        ProviderKind.ANTHROPIC -> "anthropic"
        ProviderKind.LLM_ROUTER -> "openrouter"
        ProviderKind.DEEPSEEK -> "deepseek"
        ProviderKind.KIMI -> "kimi-coding"
        ProviderKind.OPENCODE_ZEN -> "opencode-zen"
        ProviderKind.NVIDIA_NIM -> "nvidia"
        ProviderKind.CUSTOM -> "custom"
    }

    /**
     * The canonical id for a Hermes-native provider id. Hermes's
     * catalogue ids ARE the canonical ids (including `custom`, the
     * Custom/FreeLLM endpoint), so this is the identity mapping — it
     * exists so call sites name the conversion explicitly and any future
     * Hermes id rename has exactly one place to land.
     */
    fun canonicalForHermes(hermesProviderId: String): String = hermesProviderId
}

/**
 * The one-time v19 migration plan: which old (pre-unification) vault pools
 * feed which canonical provider pool, in priority order.
 *
 * Priority, per canonical provider: the Hermes setup pool first (the key
 * the user most recently managed through the Hermes surfaces), then the
 * currently selected agent's profile pool, then every remaining profile
 * pool. The executor ([ProviderKeyMigrator]) merges without loss and lets
 * the highest-priority source's active key become the unified active key.
 * Pure and side-effect free; [AppPreferences] owns the one-time flag.
 */
object ProviderKeyMigration {

    /** One old pool feeding one canonical provider pool. */
    data class PoolCopy(val sourcePoolId: String, val canonicalId: String)

    fun plan(hermesProviderId: String, selectedKind: ProviderKind?): List<PoolCopy> {
        val result = mutableListOf<PoolCopy>()
        fun add(sourcePoolId: String, canonicalId: String) {
            if (result.none { it.sourcePoolId == sourcePoolId }) {
                result += PoolCopy(sourcePoolId, canonicalId)
            }
        }
        add(HERMES_SETUP_VAULT_ID, ProviderKeys.canonicalForHermes(hermesProviderId))
        selectedKind?.let { add(it.name, ProviderKeys.canonicalForKind(it)) }
        ProviderKind.entries.forEach { add(it.name, ProviderKeys.canonicalForKind(it)) }
        return result
    }
}

/**
 * Executes a [ProviderKeyMigration] plan against a [KeyPoolStore].
 *
 * Rules (pinned by ProviderKeyMigrationTest):
 * - A canonical pool that already holds any key is left completely
 *   untouched — a stored unified value is never overwritten, merged into,
 *   or re-activated by migration.
 * - Otherwise every distinct secret from the source pools is copied
 *   (no key is lost; identical secrets are copied once), keeping each
 *   key's display name.
 * - The unified pool's active key becomes the ACTIVE key of the
 *   highest-priority source pool that had one — the Hermes setup pool
 *   first, then the currently selected agent's pool.
 * - Source pools are only read, never modified or deleted.
 */
object ProviderKeyMigrator {

    fun migrate(store: KeyPoolStore, plan: List<ProviderKeyMigration.PoolCopy>) {
        val sourcesByCanonical = LinkedHashMap<String, MutableList<String>>()
        plan.forEach { copy ->
            sourcesByCanonical.getOrPut(copy.canonicalId) { mutableListOf() } += copy.sourcePoolId
        }
        for ((canonicalId, sources) in sourcesByCanonical) {
            if (store.credentials(canonicalId).isNotEmpty()) continue
            val seenSecrets = mutableSetOf<String>()
            var preferredActiveSecret: String? = null
            for (source in sources) {
                for (credential in store.credentials(source)) {
                    if (credential.isActive && preferredActiveSecret == null) {
                        preferredActiveSecret = credential.secret
                    }
                    if (!seenSecrets.add(credential.secret)) continue
                    store.add(canonicalId, credential.name, credential.secret)
                }
            }
            if (preferredActiveSecret != null) {
                store.credentials(canonicalId)
                    .firstOrNull { it.secret == preferredActiveSecret }
                    ?.let { store.activate(canonicalId, it.id) }
            }
        }
    }
}

/**
 * v27: the baked-in FreeLLM pre-connect — the second half of the "connect
 * it from inside the app" pair (the gateway pre-connect, v26, is
 * HermesGatewayPreconnect). FreeLLM is the user's own 107-model API on
 * Render; its endpoint is a public constant baked into the build
 * (`BuildConfig.FREELLM_BASE_URL`) and its key reaches the build only
 * through CI (`BuildConfig.FREELLM_KEY`, from the FREELLM_API_KEY Actions
 * secret). The key itself is never committed, never written into the
 * vault, and never shown as a stored credential.
 *
 * Two behaviours, both deliberately narrow:
 *
 * 1. **Key fallback.** When a launch/test consumer resolves the `custom`
 *    pool's key (FreeLLM is the Custom provider) and the pool holds NO
 *    stored key, the baked key is used instead. A stored key ALWAYS wins.
 *    The fallback lives only at key-resolution time in MainViewModel —
 *    the vault's own reads (`get`/`list`/`credentials`) are untouched, so
 *    the Credentials UI never displays the baked key as a stored key, and
 *    the failed-key rotation (which works off `vault.credentials`) can
 *    never see, activate, or delete it. With an empty baked key every
 *    result here is exactly the stored value — pre-v27 behaviour.
 *
 * 2. **Fresh-install default.** Only on a truly fresh install — nothing
 *    stored at all: no Hermes profile, no Claude Code profile, no shared
 *    profile, no legacy pre-v20 Hermes setup, and no key in any provider
 *    pool — the Hermes and Claude Code profiles default to the FreeLLM
 *    Custom provider with the baked endpoint. The Hermes model stays
 *    UNSET (v24: Hermes decides). The Claude model is the Custom kind's
 *    own existing default (no new default is invented here). If ANYTHING
 *    is stored, nothing is seeded — a stored provider/model/key choice
 *    is never overridden (standing rule).
 */
object FreeLlmPreconnect {

    /** Canonical pool id of the Custom / FreeLLM provider — the only pool the baked key may fill. */
    const val CUSTOM_POOL_ID: String = "custom"

    /**
     * The key a launch/test consumer should use for [poolId]: the stored
     * key when one exists (it always wins), otherwise the baked FreeLLM
     * key — and only for the `custom` pool. Every other case returns the
     * stored value untouched (including null/blank), so an empty baked
     * key changes nothing anywhere.
     */
    fun resolveKey(poolId: String, storedKey: String?, bakedKey: String): String? =
        when {
            !storedKey.isNullOrBlank() -> storedKey
            poolId == CUSTOM_POOL_ID && bakedKey.isNotBlank() -> bakedKey
            else -> storedKey
        }

    /**
     * The fresh-install guard: every input must say "nothing stored" and
     * the build must actually carry a baked key (a keyless build never
     * seeds — the pre-connect is simply inactive there). [alreadySeeded]
     * is the one-time v27 flag: once the seeding has run, profiles the
     * user later deletes are never re-defaulted behind their back.
     */
    fun shouldSeedFreshDefault(
        bakedKey: String,
        alreadySeeded: Boolean,
        hasHermesProfile: Boolean,
        hasClaudeProfile: Boolean,
        hasSharedProfile: Boolean,
        hasLegacyHermesSetup: Boolean,
        anyStoredProviderKey: Boolean,
    ): Boolean =
        bakedKey.isNotBlank() &&
            !alreadySeeded &&
            !hasHermesProfile &&
            !hasClaudeProfile &&
            !hasSharedProfile &&
            !hasLegacyHermesSetup &&
            !anyStoredProviderKey

    /**
     * The profile a fresh install seeds for [agent]: FreeLLM Custom with
     * the baked endpoint. Hermes's model is UNSET ("", v24 — Hermes
     * decides); Claude Code's model is [ProviderKind.defaultModel] of the
     * Custom kind itself — the same default a hand-created Custom profile
     * gets today. Antigravity has no provider profile (Google sign-in
     * only) and is never seeded.
     */
    fun profileFor(agent: AgentKind, endpoint: String): ProviderProfile = when (agent) {
        AgentKind.HERMES -> ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = endpoint,
            model = "",
        )
        AgentKind.CLAUDE_CODE -> ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = endpoint,
            model = ProviderKind.CUSTOM.defaultModel,
        )
        AgentKind.ANTIGRAVITY -> throw IllegalArgumentException(
            "Antigravity has no provider profile to seed",
        )
    }
}
