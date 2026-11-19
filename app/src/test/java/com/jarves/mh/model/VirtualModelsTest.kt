package com.jarves.mh.model

import com.jarves.mh.data.ProviderKeys
import com.jarves.mh.runtime.HermesProfileRoute
import com.jarves.mh.runtime.HermesProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned FreeLLM virtual models (Fusion / Auto). Picking one is a real
 * binding: the profile is rewritten to the Custom/FreeLLM configuration, so
 * what the profile shows is exactly what both bridges launch with.
 */
class VirtualModelsTest {

    @Test
    fun pinnedOptionsAreAutoFusionAutoThenFusion() {
        assertEquals(listOf("auto-fusion", "auto", "fusion"), VirtualModels.PINNED.map { it.id })
        assertEquals(listOf("Auto-Fusion (Smart)", "Auto (Fastest SOTA)", "Fusion (Multi-Model)"), VirtualModels.PINNED.map { it.label })
        assertTrue(VirtualModels.isVirtual("auto-fusion"))
        assertTrue(VirtualModels.isVirtual("fusion"))
        assertTrue(VirtualModels.isVirtual("auto"))
        assertFalse(VirtualModels.isVirtual("claude-sonnet-4-6"))
        assertFalse(VirtualModels.isVirtual(""))
    }

    @Test
    fun bindRebindsAnyProfileToTheCustomFreeLlmConfiguration() {
        val openRouter = ProviderProfile(
            ProviderKind.LLM_ROUTER,
            baseUrl = "https://openrouter.ai/api",
            model = "anthropic/claude-sonnet-5.5",
        )
        val bound = VirtualModels.bind(openRouter, VirtualModels.FUSION_ID)
        assertEquals(ProviderKind.CUSTOM, bound.kind)
        assertEquals("https://freellmapi-backup.onrender.com/v1", bound.baseUrl)
        assertEquals("https://freellmapi-backup.onrender.com/v1", bound.resolvedBaseUrl)
        assertEquals("fusion", bound.model)
    }

    @Test
    fun bindAutoFusionSetsTheLiteralAutoFusionModel() {
        val bound = VirtualModels.bind(ProviderProfile(ProviderKind.ANTHROPIC), VirtualModels.AUTO_FUSION_ID)
        assertEquals(ProviderKind.CUSTOM, bound.kind)
        assertEquals(VirtualModels.FREELLM_BASE_URL, bound.baseUrl)
        assertEquals("auto-fusion", bound.model)
    }

    @Test
    fun bindAutoSetsTheLiteralAutoModel() {
        val bound = VirtualModels.bind(ProviderProfile(ProviderKind.ANTHROPIC), VirtualModels.AUTO_ID)
        assertEquals(ProviderKind.CUSTOM, bound.kind)
        assertEquals(VirtualModels.FREELLM_BASE_URL, bound.baseUrl)
        assertEquals("auto", bound.model)
    }

    /**
     * The required proof: a profile resolved with model `fusion` yields the
     * FreeLLM base URL, key resolution from the unified store's `custom`
     * pool, and the literal model id "fusion" — through the same route
     * mapping the Hermes bridge launches with.
     */
    @Test
    fun fusionProfileResolvesToFreeLlmBaseUrlCustomPoolAndLiteralModel() {
        val bound = VirtualModels.bind(ProviderProfile(ProviderKind.LLM_ROUTER), "fusion")

        val route = HermesProfileRoute.routeFor(bound)
        assertTrue(route.custom)
        assertEquals(HermesProviderCatalog.CUSTOM_PROVIDER_KEY, route.provider)
        assertEquals(HermesProviderCatalog.CUSTOM_KEY_ENV, route.keyEnv)
        assertEquals("https://freellmapi-backup.onrender.com/v1", route.baseUrl)

        // The bridge reads the secret from this canonical pool id.
        assertEquals("custom", ProviderKeys.canonicalForKind(bound.kind))

        // The literal virtual id reaches the agent unchanged.
        assertEquals("fusion", HermesProfileRoute.modelFor(bound))
    }

    @Test
    fun freeTextModelsAreNotRebound() {
        // Binding happens only on an explicit virtual-model pick; a profile
        // the user configured by hand is never rewritten at resolve time.
        val profile = ProviderProfile(
            ProviderKind.LLM_ROUTER,
            baseUrl = "https://openrouter.ai/api",
            model = "anthropic/claude-sonnet-5.5",
        )
        val route = HermesProfileRoute.routeFor(profile)
        assertFalse(route.custom)
        assertEquals("openrouter", route.provider)
        assertEquals("anthropic/claude-sonnet-5.5", HermesProfileRoute.modelFor(profile))
    }
}
