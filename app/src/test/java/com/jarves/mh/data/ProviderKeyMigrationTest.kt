package com.jarves.mh.data

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.runtime.HERMES_SETUP_VAULT_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The canonical-id mapping both catalogues resolve through (v19). */
class ProviderKeysTest {

    @Test
    fun everyProfileKindMapsToItsCanonicalId() {
        assertEquals("claude", ProviderKeys.canonicalForKind(ProviderKind.CLAUDE))
        assertEquals("anthropic", ProviderKeys.canonicalForKind(ProviderKind.ANTHROPIC))
        assertEquals("openrouter", ProviderKeys.canonicalForKind(ProviderKind.LLM_ROUTER))
        assertEquals("deepseek", ProviderKeys.canonicalForKind(ProviderKind.DEEPSEEK))
        assertEquals("kimi-coding", ProviderKeys.canonicalForKind(ProviderKind.KIMI))
        assertEquals("opencode-zen", ProviderKeys.canonicalForKind(ProviderKind.OPENCODE_ZEN))
        assertEquals("nvidia", ProviderKeys.canonicalForKind(ProviderKind.NVIDIA_NIM))
        assertEquals("custom", ProviderKeys.canonicalForKind(ProviderKind.CUSTOM))
    }

    @Test
    fun hermesIdsAreAlreadyCanonical() {
        // Including providers only Hermes offers — they live in the same store.
        assertEquals("openrouter", ProviderKeys.canonicalForHermes("openrouter"))
        assertEquals("custom", ProviderKeys.canonicalForHermes("custom"))
        assertEquals("openai-api", ProviderKeys.canonicalForHermes("openai-api"))
        assertEquals("gemini", ProviderKeys.canonicalForHermes("gemini"))
    }

    @Test
    fun openRouterIsTheSameIdFromBothCatalogues() {
        assertEquals(
            ProviderKeys.canonicalForKind(ProviderKind.LLM_ROUTER),
            ProviderKeys.canonicalForHermes("openrouter"),
        )
    }
}

/**
 * The one-time v19 migration: old per-agent silos are merged into the
 * unified per-provider store without loss, the Hermes setup pool wins
 * the active slot, and a stored unified value is never overwritten.
 */
class ProviderKeyMigrationTest {

    /** In-memory [KeyPoolStore] with the vault's add/activate semantics. */
    private class FakeStore : KeyPoolStore {
        data class Entry(val id: String, val name: String, val secret: String, var isActive: Boolean)

        val pools = mutableMapOf<String, MutableList<Entry>>()
        private var counter = 0

        fun seed(poolId: String, name: String, secret: String, active: Boolean = false) {
            val entries = pools.getOrPut(poolId) { mutableListOf() }
            val entry = Entry("seed-${counter++}", name, secret, false)
            entries += entry
            if (active || entries.size == 1) {
                entries.forEach { it.isActive = false }
                entry.isActive = true
            }
        }

        override fun credentials(poolId: String): List<ApiKeyCredential> =
            pools[poolId].orEmpty()
                .sortedByDescending { it.isActive }
                .map { ApiKeyCredential(it.id, it.name, it.secret, it.isActive) }

        override fun add(poolId: String, name: String, secret: String): ApiKeyInfo {
            val entries = pools.getOrPut(poolId) { mutableListOf() }
            val entry = Entry("new-${counter++}", name, secret, entries.isEmpty())
            entries += entry
            return ApiKeyInfo(entry.id, entry.name, entry.isActive)
        }

        override fun activate(poolId: String, keyId: String): Boolean {
            val entries = pools[poolId] ?: return false
            if (entries.none { it.id == keyId }) return false
            entries.forEach { it.isActive = it.id == keyId }
            return true
        }

        fun secrets(poolId: String): List<String> = pools[poolId].orEmpty().map { it.secret }
        fun activeSecret(poolId: String): String? = pools[poolId].orEmpty().firstOrNull { it.isActive }?.secret
    }

    @Test
    fun planPutsHermesSetupFirstAndCoversEveryPoolOnce() {
        val plan = ProviderKeyMigration.plan("openrouter", ProviderKind.LLM_ROUTER)
        assertEquals(
            ProviderKeyMigration.PoolCopy(HERMES_SETUP_VAULT_ID, "openrouter"),
            plan.first(),
        )
        // Every source pool appears exactly once.
        assertEquals(plan.size, plan.map { it.sourcePoolId }.distinct().size)
        assertTrue(plan.any { it == ProviderKeyMigration.PoolCopy("LLM_ROUTER", "openrouter") })
        assertTrue(plan.any { it == ProviderKeyMigration.PoolCopy("ANTHROPIC", "anthropic") })
        assertTrue(plan.any { it == ProviderKeyMigration.PoolCopy("CUSTOM", "custom") })
    }

    @Test
    fun mergesBothSilosForTheSameProviderWithoutLoss() {
        // The user's v17 state: the real OpenRouter key in the Hermes
        // setup pool, a stale key in Claude's LLM_ROUTER pool.
        val store = FakeStore()
        store.seed(HERMES_SETUP_VAULT_ID, "Primary", "sk-or-real", active = true)
        store.seed("LLM_ROUTER", "Primary", "sk-or-stale", active = true)

        ProviderKeyMigrator.migrate(store, ProviderKeyMigration.plan("openrouter", ProviderKind.LLM_ROUTER))

        assertEquals(setOf("sk-or-real", "sk-or-stale"), store.secrets("openrouter").toSet())
        // The Hermes setup pool wins the active slot.
        assertEquals("sk-or-real", store.activeSecret("openrouter"))
        // Sources are left untouched.
        assertEquals(listOf("sk-or-real"), store.secrets(HERMES_SETUP_VAULT_ID))
        assertEquals(listOf("sk-or-stale"), store.secrets("LLM_ROUTER"))
    }

    @Test
    fun identicalSecretsAreCopiedOnce() {
        val store = FakeStore()
        store.seed(HERMES_SETUP_VAULT_ID, "Primary", "sk-same", active = true)
        store.seed("LLM_ROUTER", "Primary", "sk-same", active = true)

        ProviderKeyMigrator.migrate(store, ProviderKeyMigration.plan("openrouter", ProviderKind.LLM_ROUTER))

        assertEquals(listOf("sk-same"), store.secrets("openrouter"))
    }

    @Test
    fun multiKeyPoolsKeepEveryKeyAndItsName() {
        val store = FakeStore()
        store.seed("ANTHROPIC", "Work", "sk-ant-work", active = true)
        store.seed("ANTHROPIC", "Personal", "sk-ant-personal")

        ProviderKeyMigrator.migrate(store, ProviderKeyMigration.plan("custom", ProviderKind.ANTHROPIC))

        val copied = store.credentials("anthropic")
        assertEquals(setOf("sk-ant-work", "sk-ant-personal"), copied.map { it.secret }.toSet())
        assertEquals(setOf("Work", "Personal"), copied.map { it.name }.toSet())
        assertEquals("sk-ant-work", store.activeSecret("anthropic"))
    }

    @Test
    fun selectedAgentsPoolWinsWhenHermesSetupTargetsAnotherProvider() {
        // Hermes is on Custom; Claude Code (the selected agent) is on
        // DeepSeek with two keys — its active key must stay active.
        val store = FakeStore()
        store.seed(HERMES_SETUP_VAULT_ID, "Primary", "sk-freellm", active = true)
        store.seed("DEEPSEEK", "Old", "sk-ds-old")
        store.seed("DEEPSEEK", "Current", "sk-ds-current", active = true)

        ProviderKeyMigrator.migrate(store, ProviderKeyMigration.plan("custom", ProviderKind.DEEPSEEK))

        assertEquals("sk-freellm", store.activeSecret("custom"))
        assertEquals(setOf("sk-ds-old", "sk-ds-current"), store.secrets("deepseek").toSet())
        assertEquals("sk-ds-current", store.activeSecret("deepseek"))
    }

    @Test
    fun neverOverwritesAStoredUnifiedValue() {
        val store = FakeStore()
        store.seed("openrouter", "Primary", "sk-or-unified", active = true)
        store.seed(HERMES_SETUP_VAULT_ID, "Primary", "sk-or-real", active = true)
        store.seed("LLM_ROUTER", "Primary", "sk-or-stale", active = true)

        ProviderKeyMigrator.migrate(store, ProviderKeyMigration.plan("openrouter", ProviderKind.LLM_ROUTER))

        // Untouched: no merge, no re-activation.
        assertEquals(listOf("sk-or-unified"), store.secrets("openrouter"))
        assertEquals("sk-or-unified", store.activeSecret("openrouter"))
    }

    @Test
    fun emptySourcesLeaveTheUnifiedStoreEmpty() {
        val store = FakeStore()
        ProviderKeyMigrator.migrate(store, ProviderKeyMigration.plan("custom", ProviderKind.ANTHROPIC))
        assertTrue(store.pools.values.all { it.isEmpty() })
    }
}
