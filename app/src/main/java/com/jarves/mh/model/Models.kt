package com.jarves.mh.model

import com.jarves.mh.genui.GenUiMessageState
import java.time.Instant
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

enum class ProviderProtocol { CLAUDE_LOGIN, ANTHROPIC, ANTHROPIC_GATEWAY, OPENROUTER, OPENAI_RESPONSES, OPENAI_CHAT }

enum class ProviderKind(
    val title: String,
    val subtitle: String,
    val protocol: ProviderProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val experimental: Boolean = false,
    val fixedBaseUrl: Boolean = false,
    val fixedProtocol: Boolean = false,
) {
    CLAUDE("Claude subscription", "Pro, Max, Team or Enterprise", ProviderProtocol.CLAUDE_LOGIN, "", "default"),
    ANTHROPIC("Anthropic API", "Usage billed through Console", ProviderProtocol.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-4-6"),
    LLM_ROUTER("OpenRouter", "Use your OpenRouter API key", ProviderProtocol.OPENROUTER, "https://openrouter.ai/api", "~anthropic/claude-sonnet-latest"),
    DEEPSEEK("DeepSeek", "Use your DeepSeek API key", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.deepseek.com/anthropic", "deepseek-v4-flash"),
    KIMI("Kimi", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.moonshot.ai/anthropic", "kimi-k2.6", true),
    OPENCODE_ZEN(
        "OpenCode Zen",
        "Models through the OpenCode Zen gateway",
        ProviderProtocol.OPENAI_RESPONSES,
        "https://opencode.ai/zen/v1",
        "deepseek-v4-flash",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    NVIDIA_NIM(
        "NVIDIA NIM",
        "OpenAI-compatible models hosted by NVIDIA",
        ProviderProtocol.OPENAI_CHAT,
        "https://integrate.api.nvidia.com/v1",
        "qwen/qwen2.5-coder-32b-instruct",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    CUSTOM("Custom API", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "", "", true),
}

/**
 * Coding agent engine installed in the private Linux runtime.
 * Each coding agent is installed independently on demand over the shared Core runtime.
 */
enum class AgentKind(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val downloadNote: String,
) {
    HERMES(
        "hermes",
        "Hermes Agent",
        "Nous Research's self-improving agent · own memory & skills",
        "Pre-installed",
    ),
    CLAUDE_CODE(
        "claude-code",
        "Claude Code",
        "Anthropic's coding agent · broad provider support",
        "71.8 MB",
    ),
    ANTIGRAVITY(
        "antigravity",
        "Antigravity CLI",
        "Google's official coding agent · Google account",
        "39.9 MB",
    ),
    ;

    companion object {
        /**
         * Resolves a persisted agent id. Unknown ids — including agents removed
         * in later versions (e.g. the retired DeepSeek Harness, "deepseek-harness")
         * — fall back to the default agent instead of failing.
         */
        fun fromStored(value: String?): AgentKind = entries.firstOrNull {
            it.stableId == value || it.name == value
        } ?: HERMES
    }
}

/**
 * Provider kinds the app profile system offers for [AgentKind.HERMES].
 * Since v20 Hermes runs from these profiles exactly like Claude Code —
 * its separate native setup system was deleted (see
 * runtime/HermesLaunchConfig.kt for how a profile becomes a launch
 * route). Claude subscription login has no Hermes equivalent, and
 * OpenCode Zen is not offered to any agent (see [providersForAgent]).
 */
val HERMES_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.ANTHROPIC,
    ProviderKind.LLM_ROUTER,
    ProviderKind.DEEPSEEK,
    ProviderKind.KIMI,
    ProviderKind.NVIDIA_NIM,
    ProviderKind.CUSTOM,
)

/**
 * Provider choices shown for the selected coding agent. OpenCode Zen is no longer offered: its
 * gateway does not permit use from third-party clients. The enum entry stays so saved profiles
 * still load; [AppPreferences.loadProvider] moves them to the agent's default provider.
 */
fun providersForAgent(agent: AgentKind): List<ProviderKind> = when (agent) {
    AgentKind.HERMES -> ProviderKind.entries.filter { it in HERMES_PROVIDERS }
    AgentKind.CLAUDE_CODE -> ProviderKind.entries.filterNot { it == ProviderKind.OPENCODE_ZEN }
    AgentKind.ANTIGRAVITY -> emptyList()
}

data class ProviderProfile(
    val kind: ProviderKind,
    val baseUrl: String = kind.defaultBaseUrl,
    val model: String = kind.defaultModel,
    val hasSecret: Boolean = false,
    /** Comma-separated OpenRouter provider slugs, tried in order. Blank keeps automatic routing. */
    val openRouterProviderOrder: String = "",
    /** Whether OpenRouter may use providers outside [openRouterProviderOrder]. */
    val openRouterAllowFallbacks: Boolean = true,
) {
    /** Effective base URL: fixed kinds always resolve to their constant, ignoring stored drift. */
    val resolvedBaseUrl: String get() = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl

    val openRouterProviders: List<String>
        get() = openRouterProviderOrder.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
}

/**
 * Virtual models served only by the user's own FreeLLM router: `fusion`
 * fans a prompt out to a panel of models and synthesizes one answer with a
 * judge model; `auto` lets the router pick per request. They exist ONLY at
 * the FreeLLM endpoint — no other provider serves them.
 *
 * The profile model picker pins both at the top of the model list for every
 * agent and every provider (user's explicit choice). Picking one is a real
 * binding, not a label: [bind] rewrites the profile to the Custom/FreeLLM
 * configuration — provider kind CUSTOM, the FreeLLM base URL filled in
 * (still editable afterwards), the literal virtual id as the model — so the
 * profile UI itself shows the configuration that will be used, the key
 * resolves from the unified store's `custom` pool
 * (ProviderKeys.canonicalForKind(CUSTOM)), and both agent bridges pass the
 * stored base URL, key and literal model id through unchanged.
 */
object VirtualModels {
    const val FUSION_ID = "fusion"
    const val AUTO_ID = "auto"

    /** The user's FreeLLM router endpoint — the only place the virtual models exist. */
    const val FREELLM_BASE_URL = "https://freellmapi-backup.onrender.com/v1"

    data class Option(val id: String, val label: String)

    /** Pinned at the top of every profile model list, Fusion first. */
    val PINNED: List<Option> = listOf(Option(FUSION_ID, "Fusion"), Option(AUTO_ID, "Auto"))

    fun isVirtual(modelId: String): Boolean = modelId == FUSION_ID || modelId == AUTO_ID

    /**
     * Rebinds [profile] to the Custom/FreeLLM configuration a virtual-model
     * pick requires. Any previous provider/base URL is replaced — `fusion`
     * and `auto` resolve nowhere else — and the result is an ordinary,
     * fully editable Custom profile: no hidden state, no launch-time
     * rerouting.
     */
    fun bind(profile: ProviderProfile, modelId: String): ProviderProfile =
        profile.copy(kind = ProviderKind.CUSTOM, baseUrl = FREELLM_BASE_URL, model = modelId)
}

enum class ProjectKind { PROJECT, QUICK_PROJECT }

enum class ProjectType { GENERAL, ANDROID }

enum class AndroidTemplate(val label: String) {
    COMPOSE("Jetpack Compose"),
    XML("Kotlin + XML"),
}

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val language: String,
    val slug: String = projectSlug(name),
    val rootPath: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val kind: ProjectKind = ProjectKind.PROJECT,
    val type: ProjectType = ProjectType.GENERAL,
    val androidTemplate: AndroidTemplate? = null,
) {
    val formattedUpdatedAt: String
        get() {
            val diff = System.currentTimeMillis() - updatedAtMillis
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                diff < 0 || seconds < 60 -> "Just now"
                minutes < 60 -> "${minutes}m ago"
                hours < 24 -> "${hours}h ago"
                days == 1L -> "Yesterday"
                days < 7 -> "${days}d ago"
                else -> {
                    val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(updatedAtMillis))
                }
            }
        }
}

fun projectSlug(name: String): String {
    val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(48)
        .trimEnd('-')
    return ascii.ifBlank { "project" }
}

data class QuickChatIdentity(val displayName: String, val slug: String)

fun generateQuickChatIdentity(usedSlugs: Set<String>, random: Random = Random.Default): QuickChatIdentity {
    val adjectives = listOf("bright", "calm", "clever", "curious", "gentle", "nimble", "quiet", "swift", "wise", "bold")
    val pioneers = listOf("turing", "lovelace", "hopper", "tesla", "curie", "ramanujan", "bose", "kalam", "faraday", "darwin")
    repeat(20) {
        val base = "${adjectives.random(random)}-${pioneers.random(random)}"
        if (base !in usedSlugs) return QuickChatIdentity(base.toDisplayName(), base)
    }
    val base = "${adjectives.random(random)}-${pioneers.random(random)}"
    val slug = generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in usedSlugs }
    return QuickChatIdentity(slug.toDisplayName(), slug)
}

private fun String.toDisplayName(): String = split('-').joinToString(" ") { word ->
    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
    val sizeBytes: Long = 0,
    /** Null until the directory's direct children have been counted off the UI thread. */
    val childCount: Int? = null,
)

enum class RiskLevel { SAFE, REVIEW, HIGH }

/**
 * Optional development toolchains the user can pick during onboarding.
 * Node.js, npm, Git, and Claude Code itself are always installed because the
 * agent runtime depends on them; these stacks add heavier extras on demand.
 */
enum class DevStack(
    val label: String,
    val description: String,
    val installsSummary: String,
) {
    WEB(
        "Web (JavaScript / TypeScript)",
        "Websites and web apps with HTML, CSS, and JS frameworks.",
        "Node.js and npm (already included)",
    ),
    PYTHON(
        "Python",
        "Scripts, automation, data work, and Python backends.",
        "python3, pip, venv, and build tools",
    ),
    ANDROID(
        "Android (Java / Kotlin)",
        "Build Android app projects and install them directly on this phone.",
        "JDK 17, ARM64 Android SDK 36, Build Tools 35, Gradle 8.14.3, and an offline Maven cache",
    ),
    CPP(
        "C / C++",
        "Fast compiled programs, algorithms, and systems code.",
        "gcc, g++, make, cmake, gdb",
    ),
    PHP(
        "PHP",
        "Websites and apps with PHP — classic sites and Laravel projects.",
        "php-cli, common extensions, and Composer",
    ),
}

data class ToolRequest(
    val approvalId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val toolName: String,
    val explanation: String,
    val affectedPaths: List<String> = emptyList(),
    val commandPreview: String? = null,
    val risk: RiskLevel,
)

sealed interface RuntimeEvent {
    val sessionId: String

    data class SessionStarted(override val sessionId: String) : RuntimeEvent
    data class AssistantDelta(override val sessionId: String, val text: String) : RuntimeEvent
    data class ReasoningProgress(override val sessionId: String, val estimatedTokens: Int) : RuntimeEvent
    data class ReasoningSummary(
        override val sessionId: String,
        val summary: String,
        val blockId: Long,
        val startsNewBlock: Boolean = false,
        val isFinal: Boolean = false,
    ) : RuntimeEvent
    data class ToolStarted(
        override val sessionId: String,
        val toolName: String,
        val detail: String,
    ) : RuntimeEvent
    data class ToolProgress(
        override val sessionId: String,
        val toolName: String,
        val detail: String,
    ) : RuntimeEvent
    data class RuntimeLog(
        override val sessionId: String,
        val title: String,
        val detail: String,
    ) : RuntimeEvent
    data class ToolRequested(override val sessionId: String, val request: ToolRequest) : RuntimeEvent
    data class ToolApproved(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolRejected(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolCompleted(override val sessionId: String, val toolName: String, val summary: String) : RuntimeEvent
    data class FilesChanged(override val sessionId: String, val changes: List<ChangeItem>) : RuntimeEvent {
        val paths: List<String> get() = changes.map { it.path }
    }
    data class PreviewStarted(override val sessionId: String, val url: String) : RuntimeEvent
    data class SessionCompleted(override val sessionId: String) : RuntimeEvent
    data class SessionFailed(override val sessionId: String, val reason: String) : RuntimeEvent
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val createdAt: Instant = Instant.now(),
    val attachments: List<ChatAttachment> = emptyList(),
    val workItems: List<ActivityItem> = emptyList(),
    val workedMillis: Long = 0L,
    /** Raw generative-ui payload JSON extracted from this message, without the prose. */
    val genUiPayload: String? = null,
    /** Persisted per-message generative-ui state (form values, decisions, submitted flag). */
    val genUiState: GenUiMessageState? = null,
)

data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class ProjectChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    /**
     * The agent this chat belongs to (v21: chats are per agent — each agent
     * has its own chat list inside a project). Pre-v21 index entries have no
     * stored agent; they are assigned on load, see [assigningAgentToLegacyChats].
     */
    val agentKind: AgentKind = AgentKind.HERMES,
)

/** Short human name used in agent-facing text (environment brief, transcripts). */
val AgentKind.shortName: String
    get() = when (this) {
        AgentKind.HERMES -> "Hermes"
        AgentKind.CLAUDE_CODE -> "Claude Code"
        AgentKind.ANTIGRAVITY -> "Antigravity"
    }

/**
 * v21 migration: chats written before per-agent chats existed carry no
 * stored agent. Those chats (identified by [legacyChatIds]) all belonged to
 * the one shared conversation the user had with whichever agent was active,
 * so they are assigned wholesale to [fallback] — the agent active for the
 * project at upgrade time. Chats that already carry an agent are untouched.
 */
fun List<ProjectChat>.assigningAgentToLegacyChats(
    legacyChatIds: Set<String>,
    fallback: AgentKind,
): List<ProjectChat> =
    if (legacyChatIds.isEmpty()) this
    else map { chat -> if (chat.id in legacyChatIds) chat.copy(agentKind = fallback) else chat }

enum class DiffLineType { CONTEXT, ADDITION, DELETION, INFO }

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

data class ChangeItem(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val diffLines: List<DiffLine> = emptyList(),
    val binary: Boolean = false,
    val accepted: Boolean? = null,
)

data class ActivityItem(
    val title: String,
    val detail: String,
    val isComplete: Boolean = true,
    val isCommand: Boolean = false,
)
