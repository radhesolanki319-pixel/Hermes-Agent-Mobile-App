package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The custom route's built-in defaults: a Custom API profile stored with
 * blank fields (the state older builds produced) must resolve to the
 * app's built-in FreeLLM endpoint and model at the point of use, while
 * user-entered values and every other provider kind stay untouched.
 */
class HermesRouteMapperTest {

    /** An existing install's profile: kind chosen, fields saved blank. */
    private val blankCustomProfile = ProviderProfile(ProviderKind.CUSTOM, baseUrl = "", model = "")

    @Test
    fun blankCustomProfileFallsBackToBuiltInEndpoint() {
        val route = HermesRouteMapper.forProfile(blankCustomProfile)
        assertEquals(HermesRouteMapper.CUSTOM_PROVIDER_NAME, route.provider)
        assertEquals(HermesRouteMapper.CUSTOM_KEY_ENV, route.keyEnv)
        assertEquals(HermesRouteMapper.CUSTOM_DEFAULT_BASE_URL, route.baseUrl)
        assertTrue(route.custom)
    }

    @Test
    fun whitespaceCustomUrlFallsBackToBuiltInEndpoint() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, baseUrl = "  ", model = "m")
        assertEquals(
            HermesRouteMapper.CUSTOM_DEFAULT_BASE_URL,
            HermesRouteMapper.forProfile(profile).baseUrl,
        )
    }

    @Test
    fun blankCustomModelFallsBackToBuiltInModel() {
        assertEquals(
            HermesRouteMapper.CUSTOM_DEFAULT_MODEL,
            HermesRouteMapper.resolvedModel(blankCustomProfile),
        )
    }

    @Test
    fun customProfileKeepsUserEndpointAndModel() {
        val profile = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://llm.example.com/v1",
            model = "my-model",
        )
        assertEquals("https://llm.example.com/v1", HermesRouteMapper.forProfile(profile).baseUrl)
        assertEquals("my-model", HermesRouteMapper.resolvedModel(profile))
    }

    @Test
    fun customProfileWithBlankModelKeepsUserEndpoint() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://llm.example.com/v1", model = "")
        assertEquals("https://llm.example.com/v1", HermesRouteMapper.forProfile(profile).baseUrl)
        assertEquals(HermesRouteMapper.CUSTOM_DEFAULT_MODEL, HermesRouteMapper.resolvedModel(profile))
    }

    @Test
    fun nonCustomProfilesGetNoBuiltInFallback() {
        // A first-class kind constructed with blank fields (not a state
        // the app produces, but the mapper must not invent values for
        // it): the route keeps the blank URL and the model resolves to
        // the kind's own catalog default, never the custom built-in.
        val anthropic = ProviderProfile(ProviderKind.ANTHROPIC, baseUrl = "", model = "")
        assertEquals("", HermesRouteMapper.forProfile(anthropic).baseUrl)
        assertEquals(ProviderKind.ANTHROPIC.defaultModel, HermesRouteMapper.resolvedModel(anthropic))
    }

    @Test
    fun nonCustomResolvedModelPrefersProfileThenKindDefault() {
        val picked = ProviderProfile(ProviderKind.DEEPSEEK, model = "deepseek-v4-pro")
        assertEquals("deepseek-v4-pro", HermesRouteMapper.resolvedModel(picked))
        val unpicked = ProviderProfile(ProviderKind.DEEPSEEK, model = "")
        assertEquals(ProviderKind.DEEPSEEK.defaultModel, HermesRouteMapper.resolvedModel(unpicked))
    }

    @Test
    fun kindDefaultsMirrorBuiltInConstants() {
        // Fresh profiles pre-fill from the kind defaults; point-of-use
        // resolution falls back to the mapper constants. The two copies
        // must never drift apart.
        assertEquals(HermesRouteMapper.CUSTOM_DEFAULT_BASE_URL, ProviderKind.CUSTOM.defaultBaseUrl)
        assertEquals(HermesRouteMapper.CUSTOM_DEFAULT_MODEL, ProviderKind.CUSTOM.defaultModel)
        val fresh = ProviderProfile(ProviderKind.CUSTOM)
        assertEquals(HermesRouteMapper.CUSTOM_DEFAULT_BASE_URL, fresh.baseUrl)
        assertEquals(HermesRouteMapper.CUSTOM_DEFAULT_MODEL, fresh.model)
    }

    @Test
    fun freshConfigForBlankCustomProfileCarriesBuiltInEndpointAndModel() {
        val config = HermesConfigFiles.freshConfig(
            HermesRouteMapper.forProfile(blankCustomProfile),
            HermesRouteMapper.resolvedModel(blankCustomProfile),
        )
        assertTrue(config.contains("  default: '${HermesRouteMapper.CUSTOM_DEFAULT_MODEL}'\n"))
        assertTrue(
            config.contains(
                "providers:\n  mh-custom:\n" +
                    "    base_url: '${HermesRouteMapper.CUSTOM_DEFAULT_BASE_URL}'\n" +
                    "    key_env: ${HermesRouteMapper.CUSTOM_KEY_ENV}\n",
            ),
        )
    }

    @Test
    fun mergedConfigHealsConfigWrittenWithBlankCustomFields() {
        // What the pre-fix build wrote for a blank custom profile.
        val blankRoute = HermesRoute(
            provider = HermesRouteMapper.CUSTOM_PROVIDER_NAME,
            keyEnv = HermesRouteMapper.CUSTOM_KEY_ENV,
            baseUrl = "",
            custom = true,
        )
        val writtenBefore = HermesConfigFiles.freshConfig(blankRoute, "")
        // The next launch merges with the healed route/model instead.
        val healed = HermesConfigFiles.mergedConfig(
            writtenBefore,
            HermesRouteMapper.forProfile(blankCustomProfile),
            HermesRouteMapper.resolvedModel(blankCustomProfile),
        )
        assertTrue(healed.contains("  default: '${HermesRouteMapper.CUSTOM_DEFAULT_MODEL}'\n"))
        assertTrue(healed.contains("    base_url: '${HermesRouteMapper.CUSTOM_DEFAULT_BASE_URL}'\n"))
    }
}
