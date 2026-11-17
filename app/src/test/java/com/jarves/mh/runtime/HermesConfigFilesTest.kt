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
        provider = "mh-custom",
        keyEnv = "MH_HERMES_API_KEY",
        baseUrl = "https://llm.example.com/v1",
        custom = true,
    )

    @Test
    fun freshConfigCarriesFullPowerSections() {
        val config = HermesConfigFiles.freshConfig(anthropicRoute, "claude-sonnet-4")
        assertTrue(config.contains("model:\n  provider: 'anthropic'\n  default: 'claude-sonnet-4'\n  base_url: 'https://api.anthropic.com'\n"))
        // Safety settings carried over from the Phase 1 generated config.
        assertTrue(config.contains("database:\n  journal_mode: \"delete\"\n"))
        assertTrue(config.contains("terminal:\n  backend: \"local\"\n"))
        assertTrue(config.contains("approvals:\n  mode: \"off\"\n  single_query_mode: \"approve\"\n"))
        assertTrue(config.contains("updates:\n  check: false\n"))
        // Memory, self-learning skills and delegation unlocked.
        assertTrue(config.contains("memory:\n  memory_enabled: true\n  user_profile_enabled: true\n  write_approval: false\n"))
        assertTrue(config.contains("skills:\n  write_approval: false\n  inline_shell: true\n"))
        assertTrue(config.contains("curator:\n  enabled: true\n  consolidate: true\n  prune_builtins: true\n"))
        assertTrue(
            config.contains(
                "delegation:\n  oneshot_max_children: 0\n  orchestrator_enabled: true\n" +
                    "  max_spawn_depth: 2\n  subagent_auto_approve: true\n  independent_completions: true\n",
            ),
        )
        assertTrue(
            config.contains(
                "platform_toolsets:\n  cli: [hermes-cli, x_search, video, video_gen, kanban, spotify, a2a]\n" +
                    "  acp: [hermes-acp, image_gen, tts, cronjob, kanban, clarify, video, video_gen, x_search]\n",
            ),
        )
    }

    @Test
    fun freshConfigCarriesActivationPassUnlocks() {
        val config = HermesConfigFiles.freshConfig(anthropicRoute, "claude-sonnet-4")
        // Posture pinned away from "focus", stop-time verification on.
        assertTrue(config.contains("agent:\n  coding_context: \"auto\"\n  verify_on_stop: true\n"))
        assertTrue(config.contains("checkpoints:\n  enabled: true\n"))
        assertTrue(config.contains("cron:\n  allow_agent_scheduling: true\n"))
        assertTrue(config.contains("security:\n  allow_private_urls: true\n"))
        assertTrue(config.contains("privacy:\n  redact_pii: true\n"))
        assertTrue(config.contains("sessions:\n  auto_prune: true\n  auto_archive: true\n  show_subagents: true\n"))
        assertTrue(config.contains("stt:\n  streaming: true\n"))
        assertTrue(config.contains("voice:\n  auto_tts: true\n"))
        assertTrue(config.contains("tool_loop_guardrails:\n  hard_stop_enabled: true\n"))
        // Every tool eager: the tool_search bridge is switched off.
        assertTrue(config.contains("tools:\n  tool_search:\n    enabled: \"off\"\n"))
        // Hook registrations auto-accept (top-level key).
        assertTrue(config.contains("hooks_auto_accept: true\n"))
    }

    @Test
    fun freshCustomConfigWritesNamedProviderWithoutModelBaseUrl() {
        val config = HermesConfigFiles.freshConfig(customRoute, "my-model")
        assertTrue(config.contains("  provider: 'mh-custom'\n"))
        assertFalse(config.contains("  base_url: 'https://llm.example.com/v1'\n  database:"))
        assertTrue(
            config.contains(
                "providers:\n  mh-custom:\n    base_url: 'https://llm.example.com/v1'\n    key_env: MH_HERMES_API_KEY\n",
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
        assertTrue(merged.contains("  max_tokens: 4096\n"))
        assertTrue(merged.contains("display:\n  skin: ares\n"))
        assertTrue(merged.contains("# trailing comment section\n"))
        assertTrue(merged.contains("gateway:\n  multiplex_profiles: true\n"))
        // App-owned provider/model fields are updated.
        assertTrue(merged.contains("  provider: 'anthropic'\n"))
        assertTrue(merged.contains("  default: 'claude-sonnet-4'\n"))
        assertTrue(merged.contains("  base_url: 'https://api.anthropic.com'\n"))
        assertFalse(merged.contains("openrouter"))
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
        assertTrue(merged.contains("  provider: 'mh-custom'\n"))
        // The Anthropic endpoint must not leak into the custom route.
        assertFalse(merged.contains("api.anthropic.com"))
        assertTrue(
            merged.contains(
                "providers:\n  mh-custom:\n    base_url: 'https://llm.example.com/v1'\n    key_env: MH_HERMES_API_KEY\n",
            ),
        )
    }

    @Test
    fun mergeKeepsUserChangedApprovalsButAddsMissingKeys() {
        val existing = """
            model:
              provider: 'anthropic'
              default: 'claude-sonnet-4'
            approvals:
              mode: "smart"
        """.trimIndent() + "\n"
        val merged = HermesConfigFiles.mergedConfig(existing, anthropicRoute, "claude-sonnet-4")
        // A value Hermes or the user set is theirs: not overwritten.
        assertTrue(merged.contains("approvals:\n  single_query_mode: \"approve\"\n  mode: \"smart\"\n"))
        assertFalse(merged.contains("  mode: \"off\""))
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
        assertTrue(merged.contains("delegation:\n  oneshot_max_children: 0\n"))
        assertTrue(merged.contains("platform_toolsets:\n  cli: [hermes-cli, x_search, video, video_gen, kanban, spotify, a2a]\n"))
        assertTrue(merged.contains("database:\n  journal_mode: \"delete\"\n"))
        assertTrue(merged.contains("agent:\n  coding_context: \"auto\"\n"))
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
                "  cli: [hermes-cli, x_search, video, video_gen, kanban, spotify, a2a, debugging]\n",
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
            merged.contains("  cli: [hermes-cli, x_search, video, video_gen, kanban, spotify, a2a]\n"),
        )
        assertFalse(merged.contains("- hermes-cli"))
        // And the converted shape is stable under a second merge.
        assertEquals(merged, HermesConfigFiles.mergedConfig(merged, anthropicRoute, "claude-sonnet-4"))
    }

    @Test
    fun mergePreservesUserToolSearchAndPostureChoices() {
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
        // Ensure-if-absent unlocks never override a value already set.
        assertTrue(merged.contains("  coding_context: \"focus\"\n"))
        assertTrue(merged.contains("    enabled: \"on\"\n"))
        assertFalse(merged.contains("    enabled: \"off\""))
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
