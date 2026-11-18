package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v20: Hermes launches from its profile in the shared profile system.
 * [HermesProfileRoute] turns that profile into the launch route/model —
 * translating the profile system's Claude-facing endpoints into
 * Hermes's own endpoints while the profile base URL is untouched, and
 * honouring a user-edited base URL. [HermesProfileSeeding] carries a
 * stored pre-v20 Hermes setup over to the first Hermes profile.
 */
class HermesProfileMappingTest {

    // ── Profile → route ────────────────────────────────────────────────

    @Test
    fun anthropicProfileRoutesToHermesAnthropic() {
        val profile = ProviderProfile(ProviderKind.ANTHROPIC)
        val route = HermesProfileRoute.routeFor(profile)
        assertEquals("anthropic", route.provider)
        assertEquals("ANTHROPIC_API_KEY", route.keyEnv)
        assertEquals("https://api.anthropic.com", route.baseUrl)
        assertFalse(route.custom)
        // The profile's own model pick wins.
        assertEquals("claude-sonnet-4-6", HermesProfileRoute.modelFor(profile))
    }

    @Test
    fun openRouterProfileTranslatesToHermesEndpoint() {
        // The profile stores OpenRouter's Claude-facing base
        // (https://openrouter.ai/api); Hermes needs its own /api/v1.
        val profile = ProviderProfile(ProviderKind.LLM_ROUTER)
        assertEquals("https://openrouter.ai/api", profile.baseUrl)
        val route = HermesProfileRoute.routeFor(profile)
        assertEquals("openrouter", route.provider)
        assertEquals("OPENROUTER_API_KEY", route.keyEnv)
        assertEquals("https://openrouter.ai/api/v1", route.baseUrl)
        assertFalse(route.custom)
    }

    @Test
    fun deepSeekProfileTranslatesToHermesEndpoint() {
        val route = HermesProfileRoute.routeFor(ProviderProfile(ProviderKind.DEEPSEEK))
        assertEquals("deepseek", route.provider)
        assertEquals("DEEPSEEK_API_KEY", route.keyEnv)
        assertEquals("https://api.deepseek.com/v1", route.baseUrl)
    }

    @Test
    fun kimiProfileMapsToKimiCoding() {
        val route = HermesProfileRoute.routeFor(ProviderProfile(ProviderKind.KIMI))
        assertEquals("kimi-coding", route.provider)
        assertEquals("KIMI_API_KEY", route.keyEnv)
        assertEquals("https://api.moonshot.ai/v1", route.baseUrl)
    }

    @Test
    fun nvidiaProfileRoutesToNim() {
        val route = HermesProfileRoute.routeFor(ProviderProfile(ProviderKind.NVIDIA_NIM))
        assertEquals("nvidia", route.provider)
        assertEquals("NVIDIA_API_KEY", route.keyEnv)
        assertEquals("https://integrate.api.nvidia.com/v1", route.baseUrl)
    }

    @Test
    fun userEditedBaseUrlIsHonoured() {
        val profile = ProviderProfile(
            ProviderKind.LLM_ROUTER,
            baseUrl = "https://proxy.example.com/v1",
        )
        assertEquals(
            "https://proxy.example.com/v1",
            HermesProfileRoute.routeFor(profile).baseUrl,
        )
    }

    @Test
    fun customProfileRoutesToTheCustomProviderEntry() {
        val profile = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://freellmapi-backup.onrender.com/v1",
            model = "fusion",
        )
        val route = HermesProfileRoute.routeFor(profile)
        assertTrue(route.custom)
        assertEquals(HermesProviderCatalog.CUSTOM_PROVIDER_KEY, route.provider)
        assertEquals(HermesProviderCatalog.CUSTOM_KEY_ENV, route.keyEnv)
        assertEquals("https://freellmapi-backup.onrender.com/v1", route.baseUrl)
        // The profile's free model field is the launch model, verbatim.
        assertEquals("fusion", HermesProfileRoute.modelFor(profile))
    }

    @Test
    fun blankProfileModelStaysUnset() {
        // v24: a blank profile model is UNSET for every kind — the app
        // substitutes nothing (before v24 modelFor fell back to the
        // catalog's curated default, an app-chosen model). The launch
        // omits the model and Hermes's own resolution decides.
        assertEquals(
            "",
            HermesProfileRoute.modelFor(ProviderProfile(ProviderKind.ANTHROPIC, model = "")),
        )
        assertEquals(
            "",
            HermesProfileRoute.modelFor(ProviderProfile(ProviderKind.LLM_ROUTER, model = "  ")),
        )
        assertEquals(
            "",
            HermesProfileRoute.modelFor(ProviderProfile(ProviderKind.CUSTOM, model = "")),
        )
        // A real pick still resolves verbatim, whitespace trimmed.
        assertEquals(
            "anthropic/claude-sonnet-4.6",
            HermesProfileRoute.modelFor(ProviderProfile(ProviderKind.LLM_ROUTER, model = " anthropic/claude-sonnet-4.6 ")),
        )
    }

    // ── Legacy setup → seeded profile ──────────────────────────────────

    @Test
    fun openRouterSetupSeedsAnOpenRouterProfile() {
        // The user's actual stored configuration: Hermes on OpenRouter.
        val legacy = LegacyHermesSetup("openrouter", "anthropic/claude-sonnet-4.6", "")
        val profile = HermesProfileSeeding.profileFor(legacy)
        assertEquals(ProviderKind.LLM_ROUTER, profile.kind)
        // Profile fields are in the profile system's own terms…
        assertEquals(ProviderKind.LLM_ROUTER.defaultBaseUrl, profile.baseUrl)
        assertEquals("anthropic/claude-sonnet-4.6", profile.model)
        // …and no key copy is needed: the key already sits in the
        // unified "openrouter" pool the profile resolves.
        assertNull(HermesProfileSeeding.keyCopySourcePool(legacy))
        // End to end: the seeded profile launches on Hermes's own
        // OpenRouter endpoint with the stored model.
        val route = HermesProfileRoute.routeFor(profile)
        assertEquals("openrouter", route.provider)
        assertEquals("https://openrouter.ai/api/v1", route.baseUrl)
        assertEquals("OPENROUTER_API_KEY", route.keyEnv)
        assertEquals("anthropic/claude-sonnet-4.6", HermesProfileRoute.modelFor(profile))
    }

    @Test
    fun customFusionSetupSeedsTheEndpointWithoutTheVirtualModel() {
        // v23/v24: the pre-v20 setup's stored `fusion` was that
        // system's factory default, not a pick made in the profile
        // system — the seeding must NOT turn it into a Fusion binding.
        // The stored endpoint still carries over verbatim; the model
        // seeds UNSET (empty), never the virtual id.
        val legacy = LegacyHermesSetup(
            "custom",
            "fusion",
            "https://freellmapi-backup.onrender.com/v1",
        )
        val profile = HermesProfileSeeding.profileFor(legacy)
        assertEquals(ProviderKind.CUSTOM, profile.kind)
        assertEquals("https://freellmapi-backup.onrender.com/v1", profile.baseUrl)
        assertEquals("", profile.model)
        assertNull(HermesProfileSeeding.keyCopySourcePool(legacy))
        val route = HermesProfileRoute.routeFor(profile)
        assertTrue(route.custom)
        assertEquals("https://freellmapi-backup.onrender.com/v1", route.baseUrl)
        assertEquals("", HermesProfileRoute.modelFor(profile))
    }

    @Test
    fun virtualModelOnAMappableProviderSeedsUnset() {
        // The user's actual device state: the legacy setup's stored
        // model was `auto`. Seeded onto OpenRouter it must come out
        // UNSET (v24) — never the virtual id, and no provider default
        // substituted (v23 substituted the kind default; the user's
        // "Hermes khud decide kare" order removed that too).
        val legacy = LegacyHermesSetup("openrouter", "auto", "")
        val profile = HermesProfileSeeding.profileFor(legacy)
        assertEquals(ProviderKind.LLM_ROUTER, profile.kind)
        assertEquals("", profile.model)

        val anthropic = HermesProfileSeeding.profileFor(LegacyHermesSetup("anthropic", "fusion", ""))
        assertEquals(ProviderKind.ANTHROPIC, anthropic.kind)
        assertEquals("", anthropic.model)
    }

    @Test
    fun customRealModelSetupStillSeedsVerbatim() {
        // Only virtual ids are stripped: a real model the user stored
        // in the old setup carries over exactly, as before.
        val legacy = LegacyHermesSetup(
            "custom",
            "my-local-model",
            "http://127.0.0.1:1234/v1",
        )
        val profile = HermesProfileSeeding.profileFor(legacy)
        assertEquals(ProviderKind.CUSTOM, profile.kind)
        assertEquals("http://127.0.0.1:1234/v1", profile.baseUrl)
        assertEquals("my-local-model", profile.model)
    }

    @Test
    fun anthropicAndKimiSetupsSeedTheirKinds() {
        val anthropic = HermesProfileSeeding.profileFor(LegacyHermesSetup("anthropic", "", ""))
        assertEquals(ProviderKind.ANTHROPIC, anthropic.kind)
        assertEquals(ProviderKind.ANTHROPIC.defaultBaseUrl, anthropic.baseUrl)
        // Blank stored model seeds UNSET (v24) — no kind default.
        assertEquals("", anthropic.model)

        val kimi = HermesProfileSeeding.profileFor(LegacyHermesSetup("kimi-coding", "kimi-k3", ""))
        assertEquals(ProviderKind.KIMI, kimi.kind)
        assertEquals("kimi-k3", kimi.model)
    }

    @Test
    fun unmappableProviderSeedsACustomProfileOnItsOwnEndpoint() {
        // xAI has a Hermes provider but no profile-system kind: the
        // setup carries over as a Custom profile on xAI's endpoint.
        val legacy = LegacyHermesSetup("xai", "grok-build-0.1", "")
        val profile = HermesProfileSeeding.profileFor(legacy)
        assertEquals(ProviderKind.CUSTOM, profile.kind)
        assertEquals("https://api.x.ai/v1", profile.baseUrl)
        assertEquals("grok-build-0.1", profile.model)
        // Its key lives under the legacy provider's own pool and must be
        // copied into the Custom pool the seeded profile reads.
        assertEquals("xai", HermesProfileSeeding.keyCopySourcePool(legacy))
        val route = HermesProfileRoute.routeFor(profile)
        assertTrue(route.custom)
        assertEquals("https://api.x.ai/v1", route.baseUrl)
    }

    @Test
    fun unmappableProviderWithBlankModelSeedsUnset() {
        // v24: no catalog default is substituted either — unset.
        val profile = HermesProfileSeeding.profileFor(LegacyHermesSetup("zai", "", ""))
        assertEquals(ProviderKind.CUSTOM, profile.kind)
        assertEquals("https://api.z.ai/api/paas/v4", profile.baseUrl)
        assertEquals("", profile.model)
    }

    @Test
    fun unmappableProviderWithVirtualModelSeedsUnset() {
        val profile = HermesProfileSeeding.profileFor(LegacyHermesSetup("xai", "auto", ""))
        assertEquals(ProviderKind.CUSTOM, profile.kind)
        assertEquals("https://api.x.ai/v1", profile.baseUrl)
        assertEquals("", profile.model)
    }

    @Test
    fun blankModelOnAMappableProviderSeedsUnset() {
        val profile = HermesProfileSeeding.profileFor(LegacyHermesSetup("openrouter", " ", ""))
        assertEquals("", profile.model)
    }

    // ── One-time v24 clear of the stale seeded virtual model ──────────

    @Test
    fun seededVirtualModelClearFiresOnlyForTheSeededVirtualState() {
        // The user's device: seed flag consumed, profile still on the
        // seeded virtual id → clear.
        assertTrue(HermesSeededModelClear.shouldClear(seedFlagConsumed = true, storedModel = "auto"))
        assertTrue(HermesSeededModelClear.shouldClear(seedFlagConsumed = true, storedModel = "fusion"))
        // A real model he picked is never cleared.
        assertFalse(HermesSeededModelClear.shouldClear(seedFlagConsumed = true, storedModel = "anthropic/claude-sonnet-4.6"))
        assertFalse(HermesSeededModelClear.shouldClear(seedFlagConsumed = true, storedModel = "claude-fable-5"))
        // Already unset / no profile: nothing to clear.
        assertFalse(HermesSeededModelClear.shouldClear(seedFlagConsumed = true, storedModel = ""))
        assertFalse(HermesSeededModelClear.shouldClear(seedFlagConsumed = true, storedModel = null))
        // No seeding era on this device: hands off, whatever is stored.
        assertFalse(HermesSeededModelClear.shouldClear(seedFlagConsumed = false, storedModel = "auto"))
        assertFalse(HermesSeededModelClear.shouldClear(seedFlagConsumed = false, storedModel = "fusion"))
        // Exact match only — a near-miss string is a real (if odd)
        // model id as far as the guard is concerned.
        assertFalse(HermesSeededModelClear.shouldClear(seedFlagConsumed = true, storedModel = " auto"))
        assertFalse(HermesSeededModelClear.shouldClear(seedFlagConsumed = true, storedModel = "AUTO"))
    }
}
