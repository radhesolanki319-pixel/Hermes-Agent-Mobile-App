package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Hermes launch layer after v20 deleted the separate Hermes setup
 * system: the provider catalog survives ONLY as mapping knowledge
 * (env var / Hermes endpoint / curated model per provider), and the
 * config options still change the config.yaml text the launch writes.
 * Profile → route/model mapping and the legacy-setup seeding live in
 * [HermesProfileMappingTest].
 */
class HermesLaunchConfigTest {

    private val anthropicRoute = HermesRoute(
        provider = "anthropic",
        keyEnv = "ANTHROPIC_API_KEY",
        baseUrl = "https://api.anthropic.com",
    )
    private val customRoute = HermesRoute(
        provider = "custom",
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
    fun profileRouteWritesTheCustomProviderBlock() {
        // The v20 wiring proof for a Custom (e.g. FreeLLM) profile: the
        // route derived from the profile writes exactly the custom
        // provider block the old setup's route used to write.
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://freellmapi-backup.onrender.com/v1",
            model = "fusion",
        )
        val route = HermesProfileRoute.routeFor(profile)
        val fresh = HermesConfigFiles.freshConfig(route, HermesProfileRoute.modelFor(profile))
        assertTrue(fresh.contains("model:\n  provider: 'custom'\n  default: 'fusion'\n"))
        assertTrue(
            fresh.contains(
                "providers:\n  custom:\n    name: 'Custom endpoint'\n" +
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
                "providers:\n  custom:\n    name: 'Custom endpoint'\n" +
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
}
