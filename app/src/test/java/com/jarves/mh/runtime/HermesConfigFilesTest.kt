package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesConfigFilesTest {

    private val anthropicRoute = HermesRoute(
        provider = "anthropic",
        keyEnv = "ANTHROPIC_API_KEY",
        baseUrl = "https://api.anthropic.com",
    )
    private val deepseekRoute = HermesRoute(
        provider = "deepseek",
        keyEnv = "DEEPSEEK_API_KEY",
        baseUrl = "https://api.deepseek.com/v1",
    )
    private val customRoute = HermesRoute(
        provider = "custom-provider",
        keyEnv = "CUSTOM_PROVIDER_API_KEY",
        baseUrl = "https://llm.example.com/v1",
        custom = true,
    )

    @Test
    fun freshConfigCarriesFullPowerSections() {
        val config = HermesConfigFiles.freshConfig(anthropicRoute, "claude-sonnet-4")
        assertTrue(config.contains("model:\n  provider: 'anthropic'\n  default: 'claude-sonnet-4'\n  base_url: 'https://api.anthropic.com'\n"))
        // The fixed output-token cap (v22, see MODEL_MAX_TOKENS).
        assertTrue(config.contains("  max_tokens: 8192\n"))
        // Safety settings carried over from the Phase 1 generated config.
        assertTrue(config.contains("database:\n  journal_mode: \"delete\"\n"))
        assertTrue(config.contains("terminal:\n  backend: \"local\"\n"))
        assertTrue(config.contains("approvals:\n  mode: \"off\"\n  cron_mode: \"approve\"\n"))
        assertTrue(config.contains("updates:\n  check: false\n"))
        // Memory, self-learning skills and delegation unlocked.
        assertTrue(config.contains("memory:\n  memory_enabled: true\n  user_profile_enabled: true\n  write_approval: false\n"))
        assertTrue(config.contains("skills:\n  write_approval: false\n  inline_shell: true\n"))
        assertTrue(config.contains("curator:\n  enabled: true\n  consolidate: true\n  prune_builtins: true\n"))
        assertTrue(
            config.contains(
                "delegation:\n  max_concurrent_children: 10\n  orchestrator_enabled: true\n" +
                    "  max_spawn_depth: 2\n  subagent_auto_approve: true\n",
            ),
        )
        // Keys hermes-agent 0.19.0 does not have must not be written.
        assertFalse(config.contains("oneshot_max_children"))
        assertFalse(config.contains("single_query_mode"))
        assertFalse(config.contains("independent_completions"))
        assertTrue(
            config.contains(
                "platform_toolsets:\n  cli: [hermes-cli, x_search, video, video_gen, kanban, spotify]\n" +
                    "  acp: [hermes-acp, image_gen, tts, cronjob, kanban, clarify, video, video_gen, x_search]\n",
            ),
        )
    }

    @Test
    fun freshConfigRegistersTheWarmTransportToolsets() {
        // v32: the warm transport's api_server platform gets Hermes's
        // own HTTP composite (hermes-api-server) plus the same unlock
        // extras as the CLI platform, so a warm turn has the same tools
        // as a one-shot turn. The line lands after cli/acp (insertion
        // order keeps the long-standing cli/acp pin above intact).
        val config = HermesConfigFiles.freshConfig(anthropicRoute, "claude-sonnet-4")
        assertTrue(
            config.contains(
                "  api_server: [hermes-api-server, x_search, video, video_gen, kanban, spotify]\n",
            ),
        )
        // Merging into an existing config registers it too (every
        // pre-v32 device config gains the platform on its next turn).
        val merged = HermesConfigFiles.mergedConfig(config, anthropicRoute, "claude-sonnet-4")
        assertTrue(merged.contains("  api_server: [hermes-api-server, x_search, video, video_gen, kanban, spotify]\n"))
        // And the merge is a fixed point: merging its own output
        // changes nothing (the bridge skips the write when unchanged).
        assertEquals(merged, HermesConfigFiles.mergedConfig(merged, anthropicRoute, "claude-sonnet-4"))
    }

    @Test
    fun freshConfigCarriesActivationPassUnlocks() {
        val config = HermesConfigFiles.freshConfig(anthropicRoute, "claude-sonnet-4")
        // Posture pinned away from "focus", stop-time verification on.
        assertTrue(config.contains("agent:\n  coding_context: \"auto\"\n  verify_on_stop: true\n"))
        assertTrue(config.contains("checkpoints:\n  enabled: true\n"))
        assertTrue(config.contains("security:\n  allow_private_urls: true\n"))
        assertTrue(config.contains("privacy:\n  redact_pii: true\n"))
        assertTrue(config.contains("sessions:\n  auto_prune: true\n"))
        assertTrue(config.contains("stt:\n  streaming: true\n"))
        assertTrue(config.contains("voice:\n  auto_tts: true\n"))
        assertTrue(config.contains("tool_loop_guardrails:\n  hard_stop_enabled: true\n"))
        // Every tool eager: the tool_search bridge is switched off.
        assertTrue(config.contains("tools:\n  tool_search:\n    enabled: \"off\"\n"))
        // Hook registrations auto-accept (top-level key).
        assertTrue(config.contains("hooks_auto_accept: true\n"))
    }

    @Test
    fun freshConfigWithUnsetModelOmitsTheDefaultLine() {
        // v24: model UNSET — no `default:` line is written at all, so
        // Hermes's own resolution decides. Provider, endpoint and the
        // token cap are still the app's to write.
        val config = HermesConfigFiles.freshConfig(anthropicRoute, "")
        assertTrue(config.contains("model:\n  provider: 'anthropic'\n  base_url: 'https://api.anthropic.com'\n"))
        assertFalse(config.contains("default:"))
        assertTrue(config.contains("  max_tokens: 8192\n"))
    }

    @Test
    fun mergeWithUnsetModelRemovesTheAppOwnedDefaultLine() {
        // A previous launch (model set) left `default:` in the file;
        // unsetting the model must withdraw it — leaving it would keep
        // forcing the old model. Foreign keys in the section survive.
        val existing = HermesConfigFiles.freshConfig(anthropicRoute, "claude-sonnet-4")
        assertTrue(existing.contains("  default: 'claude-sonnet-4'\n"))
        val merged = HermesConfigFiles.mergedConfig(existing, anthropicRoute, "")
        assertFalse(merged.contains("default:"))
        assertTrue(merged.contains("  provider: 'anthropic'\n"))
        assertTrue(merged.contains("  max_tokens: 8192\n"))
        // Stable: merging the merged file again changes nothing.
        assertEquals(merged, HermesConfigFiles.mergedConfig(merged, anthropicRoute, ""))
        // And setting a model again brings the line back.
        val restored = HermesConfigFiles.mergedConfig(merged, anthropicRoute, "claude-sonnet-4")
        assertTrue(restored.contains("  default: 'claude-sonnet-4'\n"))
    }

    @Test
    fun freshCustomConfigWritesNamedProviderWithoutModelBaseUrl() {
        val config = HermesConfigFiles.freshConfig(customRoute, "my-model")
        assertTrue(config.contains("  provider: 'custom-provider'\n"))
        assertFalse(config.contains("base_url"))
        assertTrue(
            config.contains(
                "providers:\n  custom-provider:\n    name: 'Custom endpoint'\n" +
                    "    api: 'https://llm.example.com/v1'\n    key_env: CUSTOM_PROVIDER_API_KEY\n",
            ),
        )
    }

    @Test
    fun mergePreservesForeignKeysCommentsAndSections() {
        val existing = """
            # My hand-tuned Hermes config
            model:
              provider: 'openrouter'
              default: 'old/model'
              base_url: 'https://openrouter.ai/api/v1'
              max_tokens: 4096
            display:
              skin: ares
            # trailing comment section
            gateway:
              multiplex_profiles: true
        """.trimIndent() + "\n"
        val merged = HermesConfigFiles.mergedConfig(existing, anthropicRoute, "claude-sonnet-4")
        // Foreign content survives verbatim.
        assertTrue(merged.contains("# My hand-tuned Hermes config\n"))
        assertTrue(merged.contains("display:\n  skin: ares\n"))
        assertTrue(merged.contains("# trailing comment section\n"))
        assertTrue(merged.contains("gateway:\n  multiplex_profiles: true\n"))
        // App-owned provider/model fields are updated.
        assertTrue(merged.contains("  provider: 'anthropic'\n"))
        assertTrue(merged.contains("  default: 'claude-sonnet-4'\n"))
        assertTrue(merged.contains("  base_url: 'https://api.anthropic.com'\n"))
        assertFalse(merged.contains("openrouter"))
        // model.max_tokens is app-owned too (v22): the fixed 8192 cap
        // replaces the hand-written 4096 — without a cap Hermes asks for
        // the model's full output limit and OpenRouter answers 402 on a
        // low balance.
        assertTrue(merged.contains("  max_tokens: 8192\n"))
        assertFalse(merged.contains("max_tokens: 4096"))
    }

    @Test
    fun mergeProviderSwitchUpdatesRouteFields() {
        val existing = HermesConfigFiles.freshConfig(anthropicRoute, "claude-sonnet-4")
        val merged = HermesConfigFiles.mergedConfig(existing, deepseekRoute, "deepseek-chat")
        assertTrue(merged.contains("  provider: 'deepseek'\n"))
        assertTrue(merged.contains("  default: 'deepseek-chat'\n"))
        assertTrue(merged.contains("  base_url: 'https://api.deepseek.com/v1'\n"))
        assertFalse(merged.contains("anthropic"))
    }

    @Test
    fun mergeToCustomRemovesStaleModelBaseUrlAndAddsProviderBlock() {
        val existing = HermesConfigFiles.freshConfig(anthropicRoute, "claude-sonnet-4")
        val merged = HermesConfigFiles.mergedConfig(existing, customRoute, "my-model")
        assertTrue(merged.contains("  provider: 'custom-provider'\n"))
        // The Anthropic endpoint must not leak into the custom route.
        assertFalse(merged.contains("api.anthropic.com"))
        assertTrue(
            merged.contains(
                "providers:\n  custom-provider:\n    name: 'Custom endpoint'\n" +
                    "    api: 'https://llm.example.com/v1'\n    key_env: CUSTOM_PROVIDER_API_KEY\n",
            ),
        )
    }

    @Test
    fun mergeAppliesAppApprovalPolicyOverExistingValues() {
        val existing = """
            model:
              provider: 'anthropic'
              default: 'claude-sonnet-4'
            approvals:
              mode: "smart"
        """.trimIndent() + "\n"
        val merged = HermesConfigFiles.mergedConfig(existing, anthropicRoute, "claude-sonnet-4")
        // approvals is an app-settings key (Hermes options card): the
        // stored policy wins over a value left by an older build or a
        // guest-terminal edit, exactly as re-running `hermes setup`
        // would rewrite it.
        assertTrue(merged.contains("approvals:\n  mode: \"off\"\n  cron_mode: \"approve\"\n"))
        assertFalse(merged.contains("\"smart\""))
        assertFalse(merged.contains("single_query_mode"))
    }

    @Test
    fun mergeAddsMissingUnlockSectionsToMinimalConfig() {
        val existing = """
            model:
              provider: 'anthropic'
              default: 'claude-sonnet-4'
        """.trimIndent() + "\n"
        val merged = HermesConfigFiles.mergedConfig(existing, anthropicRoute, "claude-sonnet-4")
        assertTrue(merged.contains("memory:\n  memory_enabled: true\n"))
        // Controlled keys append at the end of an existing section on
        // merge (fresh configs write them first); both shapes are the
        // same YAML mapping.
        assertTrue(merged.contains("delegation:\n"))
        assertTrue(merged.contains("  max_concurrent_children: 10\n"))
        assertTrue(merged.contains("platform_toolsets:\n  cli: [hermes-cli, x_search, video, video_gen, kanban, spotify]\n"))
        assertTrue(merged.contains("database:\n  journal_mode: \"delete\"\n"))
        assertTrue(merged.contains("  coding_context: \"auto\"\n"))
        assertTrue(merged.contains("tools:\n  tool_search:\n    enabled: \"off\"\n"))
        assertTrue(merged.contains("hooks_auto_accept: true\n"))
    }

    @Test
    fun mergeUpgradesPlatformToolsetsAsUnionAndKeepsOtherPlatforms() {
        // A config written by the previous pass: shorter cli list, plus a
        // hand-added toolset and another platform's list (user state).
        val existing = """
            model:
              provider: 'anthropic'
              default: 'claude-sonnet-4'
            platform_toolsets:
              cli: [hermes-cli, x_search, video, video_gen, debugging]
              telegram: [hermes-telegram]
        """.trimIndent() + "\n"
        val merged = HermesConfigFiles.mergedConfig(existing, anthropicRoute, "claude-sonnet-4")
        assertTrue(
            merged.contains(
                "  cli: [hermes-cli, x_search, video, video_gen, kanban, spotify, debugging]\n",
            ),
        )
        assertTrue(
            merged.contains(
                "  acp: [hermes-acp, image_gen, tts, cronjob, kanban, clarify, video, video_gen, x_search]\n",
            ),
        )
        // The other platform's list is user state: untouched.
        assertTrue(merged.contains("  telegram: [hermes-telegram]\n"))
    }

    @Test
    fun mergeHandlesBlockStylePlatformToolsets() {
        // Hermes's own YAML writer emits block-style lists.
        val existing = """
            model:
              provider: 'anthropic'
              default: 'claude-sonnet-4'
            platform_toolsets:
              cli:
              - hermes-cli
              - video
        """.trimIndent() + "\n"
        val merged = HermesConfigFiles.mergedConfig(existing, anthropicRoute, "claude-sonnet-4")
        assertTrue(
            merged.contains("  cli: [hermes-cli, x_search, video, video_gen, kanban, spotify]\n"),
        )
        assertFalse(merged.contains("- hermes-cli"))
        // And the converted shape is stable under a second merge.
        assertEquals(merged, HermesConfigFiles.mergedConfig(merged, anthropicRoute, "claude-sonnet-4"))
    }

    @Test
    fun mergeAppliesAppPostureAndToolSearchButKeepsUncontrolledKeys() {
        val existing = """
            model:
              provider: 'anthropic'
              default: 'claude-sonnet-4'
            agent:
              coding_context: "focus"
            tools:
              tool_search:
                enabled: "on"
            hooks_auto_accept: false
        """.trimIndent() + "\n"
        val merged = HermesConfigFiles.mergedConfig(existing, anthropicRoute, "claude-sonnet-4")
        // coding_context and tool_search are app-settings keys: the
        // stored options replace values left in the file.
        assertTrue(merged.contains("  coding_context: \"auto\"\n"))
        assertTrue(merged.contains("    enabled: \"off\"\n"))
        assertFalse(merged.contains("\"focus\""))
        // Keys the settings do not own keep their ensure-if-absent contract.
        assertEquals(1, merged.split("hooks_auto_accept").size - 1)
        assertTrue(merged.contains("hooks_auto_accept: false\n"))
    }

    @Test
    fun launchEnvironmentCarriesUnlockMarkers() {
        val env = hermesLaunchEnvironment(anthropicRoute, "secret-123")
        assertEquals("/root/.hermes", env["HERMES_HOME"])
        assertEquals("secret-123", env["ANTHROPIC_API_KEY"])
        // #19 cronjob_manage presence gate, #86 hook consent, #87 project plugins.
        assertEquals("1", env["HERMES_INTERACTIVE"])
        assertEquals("1", env["HERMES_ACCEPT_HOOKS"])
        assertEquals("1", env["HERMES_ENABLE_PROJECT_PLUGINS"])
        assertEquals(5, env.size)
    }

    @Test
    fun mergeIsIdempotent() {
        val existing = """
            # comment
            model:
              provider: 'openrouter'
              default: 'old/model'
            display:
              skin: ares
        """.trimIndent() + "\n"
        val once = HermesConfigFiles.mergedConfig(existing, customRoute, "my-model")
        val twice = HermesConfigFiles.mergedConfig(once, customRoute, "my-model")
        assertEquals(once, twice)
        val fresh = HermesConfigFiles.freshConfig(deepseekRoute, "deepseek-chat")
        assertEquals(fresh, HermesConfigFiles.mergedConfig(fresh, deepseekRoute, "deepseek-chat"))
    }

    @Test
    fun envMergeKeepsForeignKeysAndReplacesOwnedOnes() {
        val existing = """
            # user keys
            TAVILY_API_KEY=tavily-secret
            DEEPSEEK_API_KEY=stale-deepseek
            OPENROUTER_API_KEY=stale-openrouter
        """.trimIndent() + "\n"
        val merged = HermesConfigFiles.mergedEnv(existing, anthropicRoute, "fresh-anthropic")
        assertTrue(merged.contains("# user keys\n"))
        assertTrue(merged.contains("TAVILY_API_KEY=tavily-secret\n"))
        assertFalse(merged.contains("stale-deepseek"))
        assertFalse(merged.contains("stale-openrouter"))
        assertTrue(merged.endsWith("ANTHROPIC_API_KEY=fresh-anthropic\n"))
    }

    @Test
    fun envMergeOnEmptyEnvWritesJustTheKey() {
        assertEquals(
            "DEEPSEEK_API_KEY=abc\n",
            HermesConfigFiles.mergedEnv("", deepseekRoute, "abc"),
        )
    }
}
