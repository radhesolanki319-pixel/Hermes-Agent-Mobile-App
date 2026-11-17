package com.jarves.mh.runtime

/**
 * Hermes Agent's own setup/settings model for the app — the source of truth
 * for how Hermes runs, replacing the app's generic ProviderProfile system for
 * the HERMES agent (Claude Code and Antigravity keep using provider profiles).
 *
 * Everything here mirrors the PyPI **hermes-agent 0.19.0** release (the
 * version the app installs), inventoried in
 * `harness_genui/HERMES_0190_INVENTORY.md`:
 *
 *  - the provider catalog is Hermes's canonical `hermes model` list
 *    restricted to the providers whose setup is an API key (the generic
 *    `_model_flow_api_key_provider` flow plus the key-based bespoke flows).
 *    OAuth providers (nous, openai-codex, xai-oauth, qwen-oauth,
 *    minimax-oauth), the external-process copilot-acp, virtual moa, and the
 *    SDK-auth vertex/bedrock are sign-in flows that store tokens in
 *    `auth.json`; they cannot be served by a key field and are not listed.
 *  - [HermesProviderDef.defaultModel] is the first entry of Hermes's curated
 *    `_PROVIDER_MODELS` list for that provider — the model `hermes model`
 *    pre-selects. Hermes itself has no hard per-provider default; the user's
 *    pick is what lands in `model.default`.
 *  - a custom endpoint is written the way `hermes model`'s named-custom flow
 *    resolves it: a keyed entry under `providers:` in config.yaml whose
 *    `key_env` names the environment variable holding the key, with the key
 *    itself kept in `.env` (never inline in config.yaml).
 *
 * The values are persisted by AppPreferences (ids/options) and ApiKeyVault
 * (the API key under [HERMES_SETUP_VAULT_ID]) and materialised into the
 * guest's `/root/.hermes/config.yaml` + `.env` on every launch by
 * [HermesConfigFiles] — the same files `hermes setup` would write.
 */

/** ApiKeyVault pool id holding the API key for the Hermes-native setup. */
const val HERMES_SETUP_VAULT_ID = "HERMES_SETUP"

/**
 * One Hermes provider as `hermes model` knows it: the canonical slug written
 * to `model.provider`, the environment variable its API key is stored under,
 * its default endpoint and the curated model the picker pre-selects.
 */
data class HermesProviderDef(
    val id: String,
    val displayName: String,
    val keyEnv: String,
    val baseUrl: String,
    val defaultModel: String,
)

/**
 * The launch route derived from the Hermes-native settings: which provider
 * id Hermes is told to use (`--provider`), which env var carries the key,
 * and the endpoint for the model section / custom provider entry.
 */
data class HermesRoute(
    val provider: String,
    val keyEnv: String,
    val baseUrl: String,
    val custom: Boolean = false,
)

/** Hermes's provider registry (0.19.0), API-key providers plus Custom. */
object HermesProviderCatalog {
    const val CUSTOM_ID = "custom"

    /** Key of the app-managed entry under `providers:` for a custom endpoint. */
    const val CUSTOM_PROVIDER_KEY = "custom-provider"

    /** Display name written for the custom provider entry. */
    const val CUSTOM_PROVIDER_NAME = "Custom endpoint"

    /** Env var the custom endpoint's key is stored under in the guest `.env`. */
    const val CUSTOM_KEY_ENV = "CUSTOM_PROVIDER_API_KEY"

    /** Provider entry for the Custom endpoint row of the picker. */
    val CUSTOM_DEF = HermesProviderDef(
        id = CUSTOM_ID,
        displayName = "Custom endpoint",
        keyEnv = CUSTOM_KEY_ENV,
        baseUrl = "",
        defaultModel = "",
    )

    val PROVIDERS: List<HermesProviderDef> = listOf(
        HermesProviderDef("anthropic", "Anthropic", "ANTHROPIC_API_KEY", "https://api.anthropic.com", "claude-fable-5"),
        HermesProviderDef("openrouter", "OpenRouter", "OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.6"),
        HermesProviderDef("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", "https://api.deepseek.com/v1", "deepseek-v4-pro"),
        HermesProviderDef("kimi-coding", "Kimi / Kimi Coding Plan", "KIMI_API_KEY", "https://api.moonshot.ai/v1", "kimi-k3"),
        HermesProviderDef("nvidia", "NVIDIA NIM", "NVIDIA_API_KEY", "https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3-ultra-550b-a55b"),
        HermesProviderDef("openai-api", "OpenAI API", "OPENAI_API_KEY", "https://api.openai.com/v1", "gpt-5.6-sol"),
        HermesProviderDef("gemini", "Google AI Studio", "GOOGLE_API_KEY", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.1-pro-preview"),
        HermesProviderDef("xai", "xAI", "XAI_API_KEY", "https://api.x.ai/v1", "grok-build-0.1"),
        HermesProviderDef("zai", "Z.AI / GLM", "GLM_API_KEY", "https://api.z.ai/api/paas/v4", "glm-5.2"),
        HermesProviderDef("kimi-coding-cn", "Kimi / Moonshot (China)", "KIMI_CN_API_KEY", "https://api.moonshot.cn/v1", "kimi-k3"),
        HermesProviderDef("alibaba", "Qwen Cloud (Alibaba / DashScope)", "DASHSCOPE_API_KEY", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen3.7-max"),
        HermesProviderDef("alibaba-coding-plan", "Alibaba Cloud (Coding Plan)", "ALIBABA_CODING_PLAN_API_KEY", "https://coding-intl.dashscope.aliyuncs.com/v1", "qwen3.7-plus"),
        HermesProviderDef("minimax", "MiniMax", "MINIMAX_API_KEY", "https://api.minimax.io/anthropic", "MiniMax-M3"),
        HermesProviderDef("minimax-cn", "MiniMax (China)", "MINIMAX_CN_API_KEY", "https://api.minimaxi.com/anthropic", "MiniMax-M3"),
        HermesProviderDef("stepfun", "StepFun Step Plan", "STEPFUN_API_KEY", "https://api.stepfun.ai/step_plan/v1", "step-3.5-flash"),
        HermesProviderDef("copilot", "GitHub Copilot", "COPILOT_GITHUB_TOKEN", "https://api.githubcopilot.com", "gpt-5.4"),
        HermesProviderDef("huggingface", "Hugging Face", "HF_TOKEN", "https://router.huggingface.co/v1", "moonshotai/Kimi-K2.5"),
        HermesProviderDef("xiaomi", "Xiaomi MiMo", "XIAOMI_API_KEY", "https://api.xiaomimimo.com/v1", "mimo-v2.5-pro"),
        HermesProviderDef("novita", "NovitaAI", "NOVITA_API_KEY", "https://api.novita.ai/openai/v1", "moonshotai/kimi-k2.5"),
        HermesProviderDef("fireworks", "Fireworks AI", "FIREWORKS_API_KEY", "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/kimi-k2p6"),
        HermesProviderDef("kilocode", "Kilo Code", "KILOCODE_API_KEY", "https://api.kilo.ai/api/gateway", "anthropic/claude-opus-4.6"),
        HermesProviderDef("opencode-zen", "OpenCode Zen", "OPENCODE_ZEN_API_KEY", "https://opencode.ai/zen/v1", "kimi-k2.5"),
        HermesProviderDef("opencode-go", "OpenCode Go", "OPENCODE_GO_API_KEY", "https://opencode.ai/zen/go/v1", "kimi-k3"),
        HermesProviderDef("arcee", "Arcee AI", "ARCEEAI_API_KEY", "https://api.arcee.ai/api/v1", "trinity-large-thinking"),
        HermesProviderDef("gmi", "GMI Cloud", "GMI_API_KEY", "https://api.gmi-serving.com/v1", "zai-org/GLM-5.1-FP8"),
        HermesProviderDef("tencent-tokenhub", "Tencent TokenHub", "TOKENHUB_API_KEY", "https://tokenhub.tencentmaas.com/v1", "hy3-preview"),
        HermesProviderDef("deepinfra", "DeepInfra", "DEEPINFRA_API_KEY", "https://api.deepinfra.com/v1/openai", ""),
        HermesProviderDef("upstage", "Upstage Solar", "UPSTAGE_API_KEY", "https://api.upstage.ai/v1", "solar-pro3"),
        HermesProviderDef("ollama-cloud", "Ollama Cloud", "OLLAMA_API_KEY", "https://ollama.com/v1", ""),
        HermesProviderDef("lmstudio", "LM Studio", "LM_API_KEY", "http://127.0.0.1:1234/v1", ""),
        HermesProviderDef("azure-foundry", "Azure Foundry", "AZURE_FOUNDRY_API_KEY", "", ""),
    )

    /** Every provider the setup picker offers, Custom last (as in `hermes model`). */
    val ALL: List<HermesProviderDef> = PROVIDERS + CUSTOM_DEF

    fun find(id: String): HermesProviderDef? = ALL.firstOrNull { it.id == id }

    /**
     * Every `.env` key this settings system owns: each catalog provider's key
     * env plus the custom key env, plus `MH_HERMES_API_KEY` — the custom key
     * env written by older app builds — so a provider switch always replaces
     * the previous key line instead of leaving a stale credential behind.
     */
    val ALL_KEY_ENVS: List<String> =
        (ALL.map { it.keyEnv }.filter { it.isNotBlank() } + "MH_HERMES_API_KEY").distinct()
}

/**
 * The "Hermes setup" fields — provider, model, custom base URL — exactly the
 * choices `hermes setup` / `hermes model` ask for. The API key itself lives
 * in the vault (see [HermesRuntimeSettings.secret]).
 */
data class HermesSetupSettings(
    val providerId: String,
    val model: String,
    val customBaseUrl: String,
) {
    fun isCustom(): Boolean = providerId == HermesProviderCatalog.CUSTOM_ID

    fun providerDef(): HermesProviderDef? = HermesProviderCatalog.find(providerId)

    /** The model Hermes runs: the user's pick, else the provider's curated default. */
    fun resolvedModel(): String =
        model.trim().ifBlank { providerDef()?.defaultModel.orEmpty() }

    /** The launch route for these settings. */
    fun route(): HermesRoute =
        if (isCustom()) {
            HermesRoute(
                provider = HermesProviderCatalog.CUSTOM_PROVIDER_KEY,
                keyEnv = HermesProviderCatalog.CUSTOM_KEY_ENV,
                baseUrl = customBaseUrl.trim(),
                custom = true,
            )
        } else {
            val def = providerDef()
            HermesRoute(
                provider = providerId,
                keyEnv = def?.keyEnv.orEmpty(),
                baseUrl = def?.baseUrl.orEmpty(),
            )
        }
}

/**
 * The config.yaml options the app exposes as working controls. Every field
 * maps to a real hermes-agent 0.19.0 config key (see the per-field notes) and
 * is applied with set semantics by [HermesConfigFiles] on every launch — the
 * app settings are the source of truth for these keys, exactly as if the
 * user had set them with `hermes setup` / `hermes tools`.
 *
 * [FULL_POWER] reproduces the app's established full-powers unlock posture
 * as the initial values; nothing here silently weakens it.
 */
data class HermesConfigOptions(
    /** `memory.memory_enabled` — persistent memory across sessions. */
    val memoryEnabled: Boolean,
    /** `memory.user_profile_enabled` — the USER.md profile memory. */
    val memoryUserProfile: Boolean,
    /** `memory.write_approval` — stage memory writes for approval. */
    val memoryWriteApproval: Boolean,
    /** `skills.write_approval` — stage skill writes for approval. */
    val skillsWriteApproval: Boolean,
    /** `curator.enabled` — background skill maintenance. */
    val curatorEnabled: Boolean,
    /** `agent.coding_context` — one of [CODING_CONTEXTS]. */
    val codingContext: String,
    /** `tools.tool_search.enabled` — one of [TOOL_SEARCH_MODES] (strings, not bool). */
    val toolSearch: String,
    /** `delegation.max_concurrent_children` — 0.19.0 enforces a floor of 1; there is no "unlimited" value. */
    val delegationMaxChildren: Int,
    /** `approvals.mode` — one of [APPROVAL_MODES]. */
    val approvalsMode: String,
    /** `approvals.cron_mode` — one of [CRON_MODES]; the non-interactive (scheduled / one-shot) policy. */
    val approvalsCronMode: String,
    /** Extra `platform_toolsets.cli` entries beyond the always-on `hermes-cli` base bundle. */
    val cliToolsets: List<String>,
) {
    companion object {
        val CODING_CONTEXTS = listOf("auto", "focus", "on", "off")
        val TOOL_SEARCH_MODES = listOf("auto", "on", "off")
        val APPROVAL_MODES = listOf("smart", "manual", "off")
        val CRON_MODES = listOf("deny", "approve")

        /**
         * The `cli` toolsets `hermes tools` can toggle (0.19.0's
         * CONFIGURABLE_TOOLSETS minus the discord-platform-restricted pair,
         * plus `kanban`), offered by the options UI. The `hermes-cli` base
         * bundle is always written and is not part of this list.
         */
        val CLI_TOOLSET_CHOICES = listOf(
            "web", "browser", "terminal", "file", "code_execution", "vision",
            "video", "image_gen", "video_gen", "x_search", "tts", "skills",
            "todo", "memory", "context_engine", "session_search", "clarify",
            "delegation", "cronjob", "kanban", "homeassistant", "spotify",
            "yuanbao", "computer_use",
        )

        /** The established full-powers unlock posture — the initial values. */
        val FULL_POWER = HermesConfigOptions(
            memoryEnabled = true,
            memoryUserProfile = true,
            memoryWriteApproval = false,
            skillsWriteApproval = false,
            curatorEnabled = true,
            codingContext = "auto",
            toolSearch = "off",
            delegationMaxChildren = 10,
            approvalsMode = "off",
            approvalsCronMode = "approve",
            cliToolsets = listOf("x_search", "video", "video_gen", "kanban", "spotify"),
        )
    }

    /** A copy with out-of-range values pulled back to the full-power defaults. */
    fun sanitized(): HermesConfigOptions = copy(
        codingContext = codingContext.takeIf { it in CODING_CONTEXTS } ?: FULL_POWER.codingContext,
        toolSearch = toolSearch.takeIf { it in TOOL_SEARCH_MODES } ?: FULL_POWER.toolSearch,
        approvalsMode = approvalsMode.takeIf { it in APPROVAL_MODES } ?: FULL_POWER.approvalsMode,
        approvalsCronMode = approvalsCronMode.takeIf { it in CRON_MODES } ?: FULL_POWER.approvalsCronMode,
        delegationMaxChildren = delegationMaxChildren.coerceAtLeast(1),
        cliToolsets = cliToolsets.filter { it in CLI_TOOLSET_CHOICES }.distinct(),
    )
}

/** Everything one Hermes launch needs from the native settings store. */
data class HermesRuntimeSettings(
    val setup: HermesSetupSettings,
    val secret: String,
    val options: HermesConfigOptions,
)

/**
 * `.env` display masking for the raw file editor: values on `KEY=value`
 * lines are replaced with bullets so the file can be shown without exposing
 * secrets; keys, comments, blank lines and empty values pass through
 * untouched. Purely a display transform — saving always writes the real text.
 */
object HermesEnvText {
    private const val MASK = "••••••"
    private val assignment = Regex("""^(\s*(?:export\s+)?[A-Za-z_][A-Za-z0-9_]*\s*=)\s*(\S.*)?$""")

    fun mask(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val match = assignment.matchEntire(line) ?: return@joinToString line
            val value = match.groupValues[2]
            if (value.isBlank()) line else match.groupValues[1] + MASK
        }
}
