package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Hermes-native settings layer: the provider catalog mirrors
 * hermes-agent 0.19.0's registry (PyPI sdist inventory), routes are
 * derived from the Hermes setup fields, and — the wiring proof — the
 * config options change the config.yaml text the launch actually writes.
 */
class HermesNativeSettingsTest {

    private val anthropicRoute = HermesRoute(
        provider = "anthropic",
        keyEnv = "ANTHROPIC_API_KEY",
        baseUrl = "https://api.anthropic.com",
    )
    private val customRoute = HermesRoute(
        provider = "custom-provider",
        keyEnv = "CUSTOM_PROVIDER_API_KEY",
        baseUrl = "https://llm.example.com/v1",
        custom = true,
    )

    @Test
    fun catalogCarriesVerified0190Providers() {
        fun def(id: String) = HermesProviderCatalog.find(id) ?: error("missing provider $id")
        // The providers the app already supported, at their 0.19.0 values.
        assertEquals("ANTHROPIC_API_KEY", def("anthropic").keyEnv)
        assertEquals("https://api.anthropic.com", def("anthropic").baseUrl)
        assertEquals("claude-fable-5", def("anthropic").defaultModel)
        assertEquals("OPENROUTER_API_KEY", def("openrouter").keyEnv)
        assertEquals("https://openrouter.ai/api/v1", def("openrouter").baseUrl)
        assertEquals("DEEPSEEK_API_KEY", def("deepseek").keyEnv)
        assertEquals("https://api.deepseek.com/v1", def("deepseek").baseUrl)
        assertEquals("deepseek-v4-pro", def("deepseek").defaultModel)
        assertEquals("KIMI_API_KEY", def("kimi-coding").keyEnv)
        assertEquals("NVIDIA_API_KEY", def("nvidia").keyEnv)
        // More of Hermes's registry, previously unreachable in the app.
        assertEquals("OPENAI_API_KEY", def("openai-api").keyEnv)
        assertEquals("GOOGLE_API_KEY", def("gemini").keyEnv)
        assertEquals("XAI_API_KEY", def("xai").keyEnv)
        assertEquals("GLM_API_KEY", def("zai").keyEnv)
        assertEquals("HF_TOKEN", def("huggingface").keyEnv)
        // Catalog hygiene: unique ids, every key-based provider names its env.
        val ids = HermesProviderCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(HermesProviderCatalog.PROVIDERS.all { it.keyEnv.isNotBlank() && it.displayName.isNotBlank() })
        assertEquals(HermesProviderCatalog.CUSTOM_ID, HermesProviderCatalog.CUSTOM_DEF.id)
    }

    @Test
    fun routeForFirstClassProvider() {
        val route = HermesSetupSettings("deepseek", "", "").route()
        assertEquals("deepseek", route.provider)
        assertEquals("DEEPSEEK_API_KEY", route.keyEnv)
        assertEquals("https://api.deepseek.com/v1", route.baseUrl)
        assertFalse(route.custom)
    }

    @Test
    fun routeForCustomProvider() {
        val route = HermesSetupSettings("custom", "m", "  https://llm.example.com/v1  ").route()
        assertEquals(HermesProviderCatalog.CUSTOM_PROVIDER_KEY, route.provider)
        assertEquals(HermesProviderCatalog.CUSTOM_KEY_ENV, route.keyEnv)
        assertEquals("https://llm.example.com/v1", route.baseUrl)
        assertTrue(route.custom)
    }

    @Test
    fun routeForUnknownProviderIdPassesTheIdThrough() {
        // A provider id Hermes learns later still routes by name; Hermes
        // resolves it against its own registry/models.dev at runtime.
        val route = HermesSetupSettings("some-future-provider", "m", "").route()
        assertEquals("some-future-provider", route.provider)
        assertEquals("", route.keyEnv)
        assertFalse(route.custom)
    }

    @Test
    fun resolvedModelFallsBackToTheCuratedDefault() {
        assertEquals("claude-fable-5", HermesSetupSettings("anthropic", "", "").resolvedModel())
        assertEquals("my-pick", HermesSetupSettings("anthropic", " my-pick ", "").resolvedModel())
        // Providers without a curated list leave the model to the user.
        assertEquals("", HermesSetupSettings("lmstudio", "", "").resolvedModel())
    }

    @Test
    fun untouchedSetupDefaultsToTheFreeLlmFusionConfiguration() {
        // The setup screen's starting values — AppPreferences falls back to
        // these constants when nothing is stored yet: the user's own
        // FreeLLM endpoint, running its `fusion` virtual model (a panel of
        // models plus a judge), so no single model is pinned.
        assertEquals(HermesProviderCatalog.CUSTOM_ID, HermesProviderCatalog.DEFAULT_PROVIDER_ID)
        assertEquals("custom", HermesProviderCatalog.DEFAULT_PROVIDER_ID)
        assertEquals("https://freellmapi-backup.onrender.com/v1", HermesProviderCatalog.DEFAULT_CUSTOM_BASE_URL)
        assertEquals("fusion", HermesProviderCatalog.DEFAULT_MODEL)

        val setup = HermesSetupSettings(
            providerId = HermesProviderCatalog.DEFAULT_PROVIDER_ID,
            model = HermesProviderCatalog.DEFAULT_MODEL,
            customBaseUrl = HermesProviderCatalog.DEFAULT_CUSTOM_BASE_URL,
        )
        assertTrue(setup.isCustom())
        assertEquals("fusion", setup.resolvedModel())
        val route = setup.route()
        assertTrue(route.custom)
        assertEquals(HermesProviderCatalog.CUSTOM_PROVIDER_KEY, route.provider)
        assertEquals(HermesProviderCatalog.CUSTOM_KEY_ENV, route.keyEnv)
        assertEquals("https://freellmapi-backup.onrender.com/v1", route.baseUrl)

        // The first launch writes exactly that route and model.
        val fresh = HermesConfigFiles.freshConfig(route, setup.resolvedModel())
        assertTrue(fresh.contains("model:\n  provider: 'custom-provider'\n  default: 'fusion'\n"))
        assertTrue(
            fresh.contains(
                "providers:\n  custom-provider:\n    name: 'Custom endpoint'\n" +
                    "    api: 'https://freellmapi-backup.onrender.com/v1'\n    key_env: CUSTOM_PROVIDER_API_KEY\n",
            ),
        )
    }

    @Test
    fun changedOptionsChangeTheWrittenConfig() {
        val flipped = HermesConfigOptions.FULL_POWER.copy(
            memoryEnabled = false,
            memoryWriteApproval = true,
            skillsWriteApproval = true,
            curatorEnabled = false,
            codingContext = "focus",
            toolSearch = "on",
            delegationMaxChildren = 3,
            approvalsMode = "smart",
            approvalsCronMode = "deny",
            cliToolsets = listOf("web"),
        )
        // Fresh config carries the flipped values.
        val fresh = HermesConfigFiles.freshConfig(anthropicRoute, "claude-fable-5", flipped)
        assertTrue(fresh.contains("memory:\n  memory_enabled: false\n  user_profile_enabled: true\n  write_approval: true\n"))
        assertTrue(fresh.contains("skills:\n  write_approval: true\n"))
        assertTrue(fresh.contains("curator:\n  enabled: false\n"))
        assertTrue(fresh.contains("  coding_context: \"focus\"\n"))
        assertTrue(fresh.contains("tools:\n  tool_search:\n    enabled: \"on\"\n"))
        assertTrue(fresh.contains("  max_concurrent_children: 3\n"))
        assertTrue(fresh.contains("approvals:\n  mode: \"smart\"\n  cron_mode: \"deny\"\n"))
        assertTrue(fresh.contains("  cli: [hermes-cli, web]\n"))
        // And merging the flipped options over a full-power config flips
        // every controlled key in place — a changed setting changes the
        // next launch's written config.
        val fullPower = HermesConfigFiles.freshConfig(anthropicRoute, "claude-fable-5")
        val merged = HermesConfigFiles.mergedConfig(fullPower, anthropicRoute, "claude-fable-5", flipped)
        assertTrue(merged.contains("memory:\n  memory_enabled: false\n  user_profile_enabled: true\n  write_approval: true\n"))
        assertTrue(merged.contains("skills:\n  write_approval: true\n"))
        assertTrue(merged.contains("curator:\n  enabled: false\n"))
        assertTrue(merged.contains("  coding_context: \"focus\"\n"))
        assertTrue(merged.contains("    enabled: \"on\"\n"))
        assertTrue(merged.contains("  max_concurrent_children: 3\n"))
        assertFalse(merged.contains("max_concurrent_children: 10"))
        assertTrue(merged.contains("approvals:\n  mode: \"smart\"\n  cron_mode: \"deny\"\n"))
        // Uncontrolled unlock keys survive the flip untouched.
        assertTrue(merged.contains("  verify_on_stop: true\n"))
        assertTrue(merged.contains("  inline_shell: true\n"))
    }

    @Test
    fun legacyCustomBlockAndKeyAreReplaced() {
        // A config written by the previous build: mh-custom block + old key env.
        val existing = """
            model:
              provider: 'mh-custom'
              default: 'old-model'
            providers:
              mh-custom:
                base_url: 'https://old.example.com/v1'
                key_env: MH_HERMES_API_KEY
        """.trimIndent() + "\n"
        val merged = HermesConfigFiles.mergedConfig(existing, customRoute, "my-model")
        assertFalse(merged.contains("mh-custom"))
        assertTrue(
            merged.contains(
                "providers:\n  custom-provider:\n    name: 'Custom endpoint'\n" +
                    "    api: 'https://llm.example.com/v1'\n    key_env: CUSTOM_PROVIDER_API_KEY\n",
            ),
        )
        val env = HermesConfigFiles.mergedEnv(
            "MH_HERMES_API_KEY=old-secret\nTAVILY_API_KEY=tavily-secret\n",
            customRoute,
            "new-secret",
        )
        assertFalse(env.contains("MH_HERMES_API_KEY"))
        assertFalse(env.contains("old-secret"))
        assertTrue(env.contains("CUSTOM_PROVIDER_API_KEY=new-secret\n"))
        assertTrue(env.contains("TAVILY_API_KEY=tavily-secret\n"))
    }

    @Test
    fun envMergeWithBlankSecretRemovesOwnedKeysWithoutAppending() {
        val merged = HermesConfigFiles.mergedEnv(
            "ANTHROPIC_API_KEY=old\nFOO=bar\n",
            anthropicRoute,
            "",
        )
        assertEquals("FOO=bar\n", merged)
    }

    @Test
    fun envMaskHidesValuesButKeepsShape() {
        val masked = HermesEnvText.mask(
            "# comment\nANTHROPIC_API_KEY=sk-secret\nEMPTY=\nexport FOO=bar\nnot an assignment\n",
        )
        assertTrue(masked.contains("# comment\n"))
        assertTrue(masked.contains("ANTHROPIC_API_KEY=••••••\n"))
        assertFalse(masked.contains("sk-secret"))
        assertTrue(masked.contains("EMPTY=\n"))
        assertTrue(masked.contains("export FOO=••••••\n"))
        assertFalse(masked.contains("FOO=bar"))
        assertTrue(masked.contains("not an assignment\n"))
    }

    @Test
    fun sanitizedOptionsClampOutOfRangeValues() {
        val clean = HermesConfigOptions.FULL_POWER.copy(
            codingContext = "bogus",
            toolSearch = "sometimes",
            approvalsMode = "yolo",
            approvalsCronMode = "shrug",
            delegationMaxChildren = 0,
            cliToolsets = listOf("web", "a2a", "not-a-toolset"),
        ).sanitized()
        assertEquals("auto", clean.codingContext)
        assertEquals("off", clean.toolSearch)
        assertEquals("off", clean.approvalsMode)
        assertEquals("approve", clean.approvalsCronMode)
        assertEquals(1, clean.delegationMaxChildren)
        assertEquals(listOf("web"), clean.cliToolsets)
    }

    @Test
    fun fullPowerDefaultsMatchTheUnlockPosture() {
        val defaults = HermesConfigOptions.FULL_POWER
        assertTrue(defaults.memoryEnabled)
        assertTrue(defaults.memoryUserProfile)
        assertFalse(defaults.memoryWriteApproval)
        assertFalse(defaults.skillsWriteApproval)
        assertTrue(defaults.curatorEnabled)
        assertEquals("auto", defaults.codingContext)
        assertEquals("off", defaults.toolSearch)
        assertEquals(10, defaults.delegationMaxChildren)
        assertEquals("off", defaults.approvalsMode)
        assertEquals("approve", defaults.approvalsCronMode)
        assertEquals(
            listOf("x_search", "video", "video_gen", "kanban", "spotify"),
            defaults.cliToolsets,
        )
    }

    @Test
    fun fusionMigrationLeavesFreshInstallsAlone() {
        // Nothing ever stored: no migration — the preference getters already
        // default an untouched setup to the fusion configuration.
        assertNull(HermesSetupMigration.migratedSetup(null, null, null))
        assertNull(HermesSetupMigration.migratedSetup(null, "", ""))
    }

    @Test
    fun fusionMigrationRewritesTheOldUntouchedSetup() {
        // The pre-fusion untouched state, as older builds stored it:
        // Anthropic provider, no model, no custom base URL.
        val migrated = HermesSetupMigration.migratedSetup("anthropic", "", "")
        assertEquals(
            HermesSetupSettings(
                providerId = HermesProviderCatalog.DEFAULT_PROVIDER_ID,
                model = HermesProviderCatalog.DEFAULT_MODEL,
                customBaseUrl = HermesProviderCatalog.DEFAULT_CUSTOM_BASE_URL,
            ),
            migrated,
        )
        // Absent model/base-URL values (provider stored alone) migrate the same.
        assertEquals(migrated, HermesSetupMigration.migratedSetup("anthropic", null, null))
        // And the migrated setup really resolves to the fusion route.
        assertEquals("fusion", migrated!!.resolvedModel())
        assertTrue(migrated.isCustom())
    }

    @Test
    fun fusionMigrationLeavesDeliberateChoicesAlone() {
        // Anthropic picked WITH a model is a deliberate setup, not the old default.
        assertNull(HermesSetupMigration.migratedSetup("anthropic", "claude-sonnet-4.6", ""))
        // A stored custom base URL means the old state was not untouched either.
        assertNull(HermesSetupMigration.migratedSetup("anthropic", "", "https://llm.example.com/v1"))
        // Any other provider is a deliberate choice.
        assertNull(HermesSetupMigration.migratedSetup("openrouter", "", ""))
        // Already on the fusion configuration: nothing to do.
        assertNull(
            HermesSetupMigration.migratedSetup(
                HermesProviderCatalog.DEFAULT_PROVIDER_ID,
                HermesProviderCatalog.DEFAULT_MODEL,
                HermesProviderCatalog.DEFAULT_CUSTOM_BASE_URL,
            ),
        )
    }
}
