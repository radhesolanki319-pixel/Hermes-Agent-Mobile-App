package com.jarves.mh.data

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v27: the baked-in FreeLLM pre-connect (see [FreeLlmPreconnect]).
 * Placeholder key strings only — no real key material in tests.
 */
class FreeLlmPreconnectTest {

    private val baked = "baked-test-key"
    private val endpoint = "https://freellmapi-backup.onrender.com/v1"

    // --- resolveKey: the baked-key fallback ---

    @Test
    fun storedKeyAlwaysWinsOverBaked() {
        assertEquals(
            "user-key",
            FreeLlmPreconnect.resolveKey(FreeLlmPreconnect.CUSTOM_POOL_ID, "user-key", baked),
        )
    }

    @Test
    fun bakedKeyFillsBlankCustomPool() {
        assertEquals(
            baked,
            FreeLlmPreconnect.resolveKey(FreeLlmPreconnect.CUSTOM_POOL_ID, null, baked),
        )
        assertEquals(
            baked,
            FreeLlmPreconnect.resolveKey(FreeLlmPreconnect.CUSTOM_POOL_ID, "", baked),
        )
        assertEquals(
            baked,
            FreeLlmPreconnect.resolveKey(FreeLlmPreconnect.CUSTOM_POOL_ID, "  ", baked),
        )
    }

    @Test
    fun bakedKeyNeverFillsOtherPools() {
        assertNull(FreeLlmPreconnect.resolveKey("openrouter", null, baked))
        assertNull(FreeLlmPreconnect.resolveKey("anthropic", null, baked))
        // Non-custom pools get their stored value back untouched, blank included.
        assertEquals("", FreeLlmPreconnect.resolveKey("deepseek", "", baked))
        assertEquals(
            "user-key",
            FreeLlmPreconnect.resolveKey("openrouter", "user-key", baked),
        )
    }

    @Test
    fun emptyBakedKeyChangesNothing() {
        assertNull(FreeLlmPreconnect.resolveKey(FreeLlmPreconnect.CUSTOM_POOL_ID, null, ""))
        assertEquals(
            "",
            FreeLlmPreconnect.resolveKey(FreeLlmPreconnect.CUSTOM_POOL_ID, "", ""),
        )
        assertEquals(
            "user-key",
            FreeLlmPreconnect.resolveKey(FreeLlmPreconnect.CUSTOM_POOL_ID, "user-key", ""),
        )
    }

    @Test
    fun customPoolIdIsTheCanonicalCustomId() {
        assertEquals(
            ProviderKeys.canonicalForKind(ProviderKind.CUSTOM),
            FreeLlmPreconnect.CUSTOM_POOL_ID,
        )
    }

    // --- shouldSeedFreshDefault: the fresh-install guard ---

    private fun seed(
        bakedKey: String = baked,
        alreadySeeded: Boolean = false,
        hasHermesProfile: Boolean = false,
        hasClaudeProfile: Boolean = false,
        hasSharedProfile: Boolean = false,
        hasLegacyHermesSetup: Boolean = false,
        anyStoredProviderKey: Boolean = false,
    ): Boolean = FreeLlmPreconnect.shouldSeedFreshDefault(
        bakedKey = bakedKey,
        alreadySeeded = alreadySeeded,
        hasHermesProfile = hasHermesProfile,
        hasClaudeProfile = hasClaudeProfile,
        hasSharedProfile = hasSharedProfile,
        hasLegacyHermesSetup = hasLegacyHermesSetup,
        anyStoredProviderKey = anyStoredProviderKey,
    )

    @Test
    fun freshInstallWithBakedKeySeeds() {
        assertTrue(seed())
    }

    @Test
    fun keylessBuildNeverSeeds() {
        assertFalse(seed(bakedKey = ""))
        assertFalse(seed(bakedKey = "  "))
    }

    @Test
    fun anyStoredTraceBlocksSeeding() {
        assertFalse(seed(alreadySeeded = true))
        assertFalse(seed(hasHermesProfile = true))
        assertFalse(seed(hasClaudeProfile = true))
        assertFalse(seed(hasSharedProfile = true))
        assertFalse(seed(hasLegacyHermesSetup = true))
        assertFalse(seed(anyStoredProviderKey = true))
    }

    // --- profileFor: the seeded profiles ---

    @Test
    fun hermesProfileIsFreeLlmCustomWithModelUnset() {
        val profile = FreeLlmPreconnect.profileFor(AgentKind.HERMES, endpoint)
        assertEquals(ProviderKind.CUSTOM, profile.kind)
        assertEquals(endpoint, profile.baseUrl)
        // v24: the Hermes model stays UNSET — Hermes decides.
        assertEquals("", profile.model)
    }

    @Test
    fun claudeProfileIsFreeLlmCustomWithItsExistingDefaultModel() {
        val profile = FreeLlmPreconnect.profileFor(AgentKind.CLAUDE_CODE, endpoint)
        assertEquals(ProviderKind.CUSTOM, profile.kind)
        assertEquals(endpoint, profile.baseUrl)
        assertEquals(ProviderKind.CUSTOM.defaultModel, profile.model)
    }

    @Test
    fun antigravityHasNoProfileToSeed() {
        assertThrows(IllegalArgumentException::class.java) {
            FreeLlmPreconnect.profileFor(AgentKind.ANTIGRAVITY, endpoint)
        }
    }
}
