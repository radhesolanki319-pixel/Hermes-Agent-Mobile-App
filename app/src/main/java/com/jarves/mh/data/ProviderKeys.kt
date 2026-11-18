package com.jarves.mh.data

import com.jarves.mh.model.ProviderKind
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
 * consumer — the Hermes bridge, the Claude Code bridge, the Hermes setup
 * card, the profile provider card, and the simple Settings → API Keys
 * screen — reads and writes that one pool, so a key entered anywhere is
 * the key everywhere.
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
