package com.jarves.mh.runtime

import com.jarves.mh.data.ProviderKeys
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.VirtualModels

/**
 * How a Hermes launch is configured, since v20 deleted Hermes's separate
 * setup system.
 *
 * Before v20, Hermes had its own provider/key/model setup inside the app
 * (a Hermes-native settings store mirroring `hermes setup`, with its own
 * provider picker, key field and model field). The user ordered it
 * removed entirely: Hermes now uses the SAME profile system as Claude
 * Code and Antigravity — the Agent screen's Hermes tab shows the shared
 * profile card (AI provider, Endpoint & protocol, AI model, Credentials,
 * Test connection) bound to Hermes's own [ProviderProfile], and the
 * launch resolves everything from that profile.
 *
 * What remains in this file is launch knowledge, not a settings store:
 *
 *  - [HermesProviderCatalog]: Hermes's provider registry (re-verified
 *    against v0.23.2 for the v40 update — every id below exists in
 *    0.23.2's registry or its model-provider plugins; upstream removed
 *    "copilot", so it is gone here too), kept
 *    ONLY as mapping knowledge — the env var each provider's key is
 *    stored under in the guest `.env`, Hermes's own endpoint for the
 *    provider, and the curated first model `hermes model` pre-selects.
 *  - [HermesProfileRoute]: turns the active Hermes profile into the
 *    launch route + model the bridge writes into `config.yaml`/`.env`.
 *  - [HermesProfileSeeding]: the one-time carry-over that seeds the
 *    first Hermes profile from a pre-v20 stored Hermes setup.
 *  - [HermesConfigOptions]: Hermes's config behaviour (memory,
 *    curator, toolsets, approvals…) as FIXED full-power constants —
 *    since v22 there is no options UI; [HermesConfigOptions.FULL_POWER]
 *    is the only value a launch ever uses.
 *  - [HermesRuntimeSettings]: everything one launch needs.
 *
 * The provider KEY itself lives in the unified provider store (see
 * `data/ProviderKeys`): an ApiKeyVault pool named by the provider's
 * canonical id, shared by every agent and resolved by the caller.
 */

/**
 * LEGACY ApiKeyVault pool id that held the Hermes-native setup's single
 * API key before v19. Since the v19 unification the setup's key lives in
 * the per-provider unified store; this pool is only ever READ by the
 * one-time migration (ProviderKeyMigration) and is otherwise untouched.
 */
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
 * The launch route derived from the active Hermes profile: which provider
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
        displayName = "FreeLLM API (Render)",
        keyEnv = CUSTOM_KEY_ENV,
        baseUrl = "https://freellmapi-backup.onrender.com/v1",
        defaultModel = "auto-fusion",
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

    /** Every provider in the registry, Custom last (as in `hermes model`). */
    val ALL: List<HermesProviderDef> = PROVIDERS + CUSTOM_DEF

    fun find(id: String): HermesProviderDef? = ALL.firstOrNull { it.id == id }

    /**
     * Every `.env` key this launch layer owns: each catalog provider's key
     * env plus the custom key env, plus `MH_HERMES_API_KEY` — the custom key
     * env written by older app builds — so a provider switch always replaces
     * the previous key line instead of leaving a stale credential behind.
     */
    val ALL_KEY_ENVS: List<String> =
        (ALL.map { it.keyEnv }.filter { it.isNotBlank() } + "MH_HERMES_API_KEY").distinct()
}

/**
 * Turns the active Hermes [ProviderProfile] into the launch route and
 * model Hermes runs with — the profile-system counterpart of the deleted
 * Hermes setup's own derivation.
 *
 * The profile's fields are written for the profile system (its base URLs
 * are the Claude-Code-facing endpoints, e.g. OpenRouter's
 * `https://openrouter.ai/api`), so the route translates through the
 * Hermes catalog: while the profile's base URL is the kind's untouched
 * default, Hermes's own endpoint for the provider is used; a base URL
 * the user actually edited in the profile is honoured as-is. The model
 * is the profile's pick, verbatim — since v24 a blank profile model
 * stays blank (UNSET): the launch omits the model entirely and Hermes's
 * own resolution decides (see [modelFor]).
 */
object HermesProfileRoute {

    fun routeFor(profile: ProviderProfile): HermesRoute {
        if (profile.kind == ProviderKind.CUSTOM) {
            return HermesRoute(
                provider = HermesProviderCatalog.CUSTOM_PROVIDER_KEY,
                keyEnv = HermesProviderCatalog.CUSTOM_KEY_ENV,
                baseUrl = profile.resolvedBaseUrl.trim(),
                custom = true,
            )
        }
        val canonicalId = ProviderKeys.canonicalForKind(profile.kind)
        val def = HermesProviderCatalog.find(canonicalId)
        val storedBaseUrl = profile.resolvedBaseUrl.trim()
        return HermesRoute(
            provider = def?.id ?: canonicalId,
            keyEnv = def?.keyEnv.orEmpty(),
            baseUrl = if (storedBaseUrl.isBlank() || storedBaseUrl == profile.kind.defaultBaseUrl) {
                def?.baseUrl.orEmpty()
            } else {
                storedBaseUrl
            },
        )
    }

    /**
     * The model Hermes runs: the profile's pick, verbatim. A blank
     * profile model is UNSET (v24 — the user's order: "Model bilkul set
     * mat karo — Hermes khud decide kare"): the bridge then passes no
     * `-m` flag and writes no `model.default`, so hermes-agent's own
     * resolution decides — verified against the pinned 0.19.0 wheel:
     * the chat transport resolves an empty model through the custom
     * provider entry's own `model` field, a localhost endpoint's
     * auto-detected model, or `get_default_model_for_provider` (the
     * provider's catalog default — for OpenRouter the cost-safe
     * catalog-labeled `z-ai/glm-5.2`, NOT this catalog's curated
     * `anthropic/claude-sonnet-4.6`); a custom endpoint has no catalog,
     * so the request goes out with an empty model and the endpoint
     * itself decides. Before v24 this function substituted the app
     * catalog's curated default for a blank model — an app-chosen
     * model, which is exactly what the user forbade.
     */
    fun modelFor(profile: ProviderProfile): String {
        val trimmed = profile.model.trim()
        if (trimmed.isNotBlank()) return trimmed
        return if (profile.kind == ProviderKind.CUSTOM) {
            HermesProviderCatalog.CUSTOM_DEF.defaultModel
        } else {
            ""
        }
    }
}

/**
 * The pre-v20 Hermes setup exactly as it was stored (Hermes-catalogue
 * provider id, model, custom base URL) — read raw from preferences by
 * AppPreferences for the one-time seeding; null when no setup was ever
 * stored.
 */
data class LegacyHermesSetup(
    val providerId: String,
    val model: String,
    val customBaseUrl: String,
)

/**
 * The one-time v20 seeding plan: the Hermes profile a stored pre-v20
 * Hermes setup carries over to.
 *
 * Providers the profile system has a kind for map onto that kind, with
 * the profile fields in the profile system's own terms (the kind's
 * default base URL — the launch route translates it to Hermes's
 * endpoint, see [HermesProfileRoute]) and the stored model kept
 * verbatim. A stored Custom setup maps to a Custom profile with its
 * base URL untouched.
 *
 * ONE exception (v23, narrowed by v24): a stored FreeLLM virtual model
 * (`fusion` / `auto`) is NEVER carried over. In the pre-v20 setup
 * system those ids were the simple surface's factory defaults — Fusion
 * was literally the default choice of the old selector — so a stored
 * virtual id is indistinguishable from "never chose anything", and
 * carrying it over manufactured a Fusion/Auto binding the user never
 * picked in the profile system (exactly what happened on the user's
 * device: the seeded Hermes profile sat on `auto` and, because model
 * picks only persisted after a successful connection test, kept
 * coming back). Since v23 the ONLY way a profile can hold a virtual
 * model is an explicit tap on its row in the model sheet
 * ([VirtualModels.bind] at the pick site).
 *
 * v24 (the user's order: "Model bilkul set mat karo — Hermes khud
 * decide kare"): a virtual OR absent stored model now seeds the
 * profile with the model UNSET (empty) — v23's fallback to the
 * provider's default model is gone, because a provider default is
 * still an app-chosen model. A real stored model still carries over
 * verbatim. With the model unset, the launch omits it entirely and
 * Hermes's own resolution decides (see [HermesProfileRoute.modelFor]).
 *
 * A Hermes-catalogue provider the profile system has NO kind for (xAI,
 * Gemini, Z.AI, …) cannot be a first-class profile; it is carried as a
 * Custom profile pointed at that provider's own endpoint with its
 * stored model, which Hermes reaches through the generic
 * custom-provider route just as it did through the old setup. Its key
 * sits in the unified pool named by the legacy provider id, so
 * [keyCopySourcePool] names that pool and the caller copies the key
 * into the Custom pool the seeded profile reads.
 *
 * Pure and side-effect free; AppPreferences owns the one-time flag and
 * MainViewModel executes the seeding (it holds the vault for the key
 * copy). A Hermes profile the user already has is never seeded over.
 */
object HermesProfileSeeding {

    /** Legacy Hermes provider ids that have a profile-system kind. */
    private val KIND_BY_LEGACY_ID: Map<String, ProviderKind> = mapOf(
        "anthropic" to ProviderKind.ANTHROPIC,
        "openrouter" to ProviderKind.LLM_ROUTER,
        "deepseek" to ProviderKind.DEEPSEEK,
        "kimi-coding" to ProviderKind.KIMI,
        "nvidia" to ProviderKind.NVIDIA_NIM,
        HermesProviderCatalog.CUSTOM_ID to ProviderKind.CUSTOM,
    )

    fun profileFor(legacy: LegacyHermesSetup): ProviderProfile {
        // Never seed a FreeLLM virtual model (v23) — see the object
        // doc. A real stored model carries over verbatim; a virtual
        // one (or a blank) seeds the model UNSET (v24): no provider
        // default is substituted, Hermes itself decides at launch.
        val carriedModel = legacy.model.trim()
            .takeUnless { VirtualModels.isVirtual(it) }
            .orEmpty()
        val kind = KIND_BY_LEGACY_ID[legacy.providerId]
        if (kind != null) {
            return ProviderProfile(
                kind = kind,
                baseUrl = if (kind == ProviderKind.CUSTOM) {
                    legacy.customBaseUrl.trim().ifBlank { kind.defaultBaseUrl }
                } else {
                    kind.defaultBaseUrl
                },
                model = carriedModel,
            )
        }
        val def = HermesProviderCatalog.find(legacy.providerId)
        return ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = def?.baseUrl.orEmpty(),
            model = carriedModel,
        )
    }

    /**
     * The canonical unified-store pool holding the legacy setup's key
     * when the seeded profile reads a different pool — only the
     * unmappable-provider case (seeded as Custom, key under the legacy
     * provider's own pool). Null when no copy is needed.
     */
    fun keyCopySourcePool(legacy: LegacyHermesSetup): String? =
        if (legacy.providerId in KIND_BY_LEGACY_ID) {
            null
        } else {
            ProviderKeys.canonicalForHermes(legacy.providerId)
        }
}

/**
 * The guard for the one-time v24 clear of the stale SEEDED virtual
 * model (the user's current device state: his Hermes profile still
 * sits on the `auto` the v20 seeding planted, and at his order —
 * "Model bilkul set mat karo — Hermes khud decide kare" — that value
 * is to be removed, leaving the model unset).
 *
 * The clear fires only when BOTH hold:
 *  - the v20 seed flag (`hermes_profile_seeded_v20`) was consumed —
 *    the profile on this device passed through the seeding era; and
 *  - the Hermes profile's stored model is exactly `fusion` or `auto`
 *    ([VirtualModels.isVirtual], exact match — no trimming, no
 *    prefix games).
 *
 * Anything else — a real model he picked, an unset model, a missing
 * profile, a device that never consumed the seed flag — is left
 * exactly as stored. The clear touches ONLY the Hermes profile's
 * model field: provider, endpoint and credentials are never read or
 * written by it, no other agent's profile is involved, and it is the
 * only migration v24 adds (flag-guarded, runs once). Pure; the
 * execution (flag + raw-key write) lives in AppPreferences /
 * MainViewModel.
 */
object HermesSeededModelClear {

    fun shouldClear(seedFlagConsumed: Boolean, storedModel: String?): Boolean =
        seedFlagConsumed && storedModel != null && VirtualModels.isVirtual(storedModel)
}

/**
 * Hermes's config.yaml behaviour as a value object. Every field maps to a
 * real hermes-agent 0.19.0 config key (see the per-field notes) and is
 * applied with set semantics by [HermesConfigFiles] on every launch.
 *
 * Since v22 the app exposes NO controls for these: at the user's order
 * the options card left the Agent screen and the launch path always
 * passes [FULL_POWER] — the app's established full-powers unlock
 * posture, now simply the fixed behaviour. (Before v22 the values came
 * from stored preferences the options UI wrote; those `hermes_opt_*`
 * keys are retired and any stored values are inert.) The class shape
 * stays so the config writer and its tests keep a typed value to work
 * with.
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
         * plus `kanban`) — the validity set [sanitized] filters against.
         * The `hermes-cli` base bundle is always written and is not part
         * of this list.
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

/** Everything one Hermes launch needs, resolved from the active Hermes profile. */
data class HermesRuntimeSettings(
    val route: HermesRoute,
    val model: String,
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
