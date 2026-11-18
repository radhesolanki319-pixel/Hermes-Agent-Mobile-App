package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * The embedded transport is Hermes 0.19.0's quiet one-shot mode
 * (`hermes chat -q <prompt> -Q`); its merged stdout/stderr stream is
 * classified by [HermesQuietOutput]. The stream-json protocol this bridge
 * was first written against (`--format stream-json`, `--query-file`)
 * exists only in post-0.19.0 Hermes source — the pinned 0.19.0 release
 * rejects both flags in argparse (exit 2, empty stdout), which is why
 * every on-device turn used to die before Hermes started. See
 * HermesQuietOutput's doc for the verified 0.19.0 output contract.
 */

internal fun hermesToolDisplayName(name: String): String = when (name.lowercase()) {
    "terminal" -> "Bash"
    "read_file" -> "Read"
    "write_file" -> "Write"
    "patch" -> "Edit"
    "search_files" -> "Search"
    "web_search" -> "Web search"
    "web_extract" -> "Web extract"
    "vision_analyze" -> "Vision"
    "execute_code" -> "Python"
    "delegate_task" -> "Delegate"
    "todo" -> "Plan"
    "clarify" -> "Clarify"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * Owns the text of HERMES_HOME's `config.yaml` and `.env`.
 *
 * config.yaml is Hermes/user state, not app scratch space: Hermes's own
 * learned settings and anything the user edits in the guest terminal live
 * in the same file, so once the file exists the bridge must never
 * regenerate it from scratch. The merge below is a conservative
 * line/section edit — the codebase has no YAML library (org.json only),
 * and Hermes writes this file in block style (top-level sections,
 * two-space child keys), which is the only shape the merge rewrites.
 * Inside that shape it:
 *
 *  - always updates the provider/model fields the app owns
 *    (`model.provider`, `model.default`, `model.base_url` and the
 *    `providers.<name>` block of a custom endpoint), because the app is
 *    where the user picks the provider;
 *  - adds the app's unlock/safety keys (see [STATIC_SECTIONS]) only where
 *    they are absent — a value already present belongs to Hermes or the
 *    user and is preserved verbatim, like every other line of the file;
 *  - applies the user's Hermes options (see [HermesConfigOptions]) with
 *    set semantics — memory, skills/curator gates, coding posture,
 *    tool_search, delegation cap and the approval policy are the app
 *    settings' keys, so the settings screen is their source of truth
 *    exactly as `hermes setup` / `hermes tools` would be on a desktop;
 *  - rewrites the `platform_toolsets` cli/acp lists it owns as a union
 *    with whatever is already there (see [ensurePlatformToolsets]), and
 *    ensures a top-level key that does not fit the section shape:
 *    `hooks_auto_accept`;
 *  - never deletes sections, keys or comments it does not own.
 *
 * Known limits: a section written in flow style (`model: {...}` on one
 * line) counts as present and is left untouched — the launch's
 * `--provider`/`-m` flags still route that run — and child keys are only
 * recognised at the standard two-space indent (four inside `providers`).
 */
internal object HermesConfigFiles {

    /**
     * Sections guaranteed in a fresh config, and added key-by-key to an
     * existing config when absent. Every value is a Hermes schema default
     * or an unlock:
     *  - `database.journal_mode "delete"`: PRoot's filesystem is not WAL
     *    crash-safe; Hermes documents "delete" for such backing stores.
     *  - `updates.check false`: the app owns the pinned install; Hermes
     *    must never self-update behind the version marker.
     *  - `skills.inline_shell` + curator consolidation/pruning: Hermes's
     *    self-learning loop (author/patch skills, background curation of
     *    agent-created skills) runs at full strength.
     *
     * The activation pass (against HERMES_HIDDEN_POWERS.md) added the
     * remaining default-off unlocks below, each ensure-if-absent like
     * the rest: delegation depth/approvals (#38/#39), skill inline
     * shell (#41), curator consolidation + built-in pruning (#43/#44),
     * stop-time verification (#49), checkpoints (#36), private-URL
     * access (#46), PII redaction (#47), session housekeeping (#50),
     * streaming STT (#52), voice auto-TTS (#53) and the tool-loop hard
     * stop (#54). Keys the user controls in the Hermes settings screen
     * (memory gates, write approvals, curator on/off, coding posture,
     * the approval policy, the delegation cap) are NOT here — they are
     * applied from [HermesConfigOptions] by [applyOptions]. Three keys
     * older builds wrote are gone because hermes-agent 0.19.0 does not
     * have them (verified against the PyPI sdist): `approvals.
     * single_query_mode`, `delegation.oneshot_max_children` (the real
     * cap is `max_concurrent_children`) and `delegation.
     * independent_completions`, plus the dead `cron.
     * allow_agent_scheduling` and `sessions.auto_archive` /
     * `show_subagents`. `platform_toolsets` is app-owned policy and
     * handled by [ensurePlatformToolsets].
     */
    private val STATIC_SECTIONS: List<Pair<String, List<String>>> = listOf(
        "database" to listOf("  journal_mode: \"delete\""),
        "terminal" to listOf("  backend: \"local\""),
        "updates" to listOf("  check: false"),
        "skills" to listOf(
            // #41: skills may pre-execute !`cmd` snippets when they
            // load. Hermes never auto-executes community-hub installs;
            // only local/agent-authored skills gain this, and those
            // already run under the ungated terminal.
            "  inline_shell: true",
        ),
        "curator" to listOf(
            // #43/#44: the full curation pass — LLM consolidation of
            // agent-created skills, and stale built-ins may be pruned.
            "  consolidate: true",
            "  prune_builtins: true",
        ),
        "delegation" to listOf(
            "  orchestrator_enabled: true",
            // #38/#39: orchestrator→worker trees (depth 2 instead of
            // flat-only), and "once" prompts inside sub-agents
            // auto-approved (the parent session already runs --yolo).
            "  max_spawn_depth: 2",
            "  subagent_auto_approve: true",
        ),
        "agent" to listOf(
            // #49: after code edits the agent must produce fresh
            // verification evidence before its final answer.
            "  verify_on_stop: true",
            // Latency (v17): cap the model's reasoning depth for chat.
            // 0.19.0 resolves agent.reasoning_effort at CLI startup
            // (hermes_constants.resolve_reasoning_config; valid levels
            // none|minimal|low|medium|high|xhigh|max|ultra) — unset
            // means the provider's default depth, which visibly
            // "thinks" even over a greeting before answering.
            // "minimal" is the lowest enabled level: near-instant
            // simple replies while tool planning keeps a sliver of
            // reasoning. Ensure-if-absent like every static here, so a
            // value Hermes or the user sets deliberately always wins.
            "  reasoning_effort: \"minimal\"",
        ),
        "checkpoints" to listOf(
            // #36: snapshot the working dir once per turn; /rollback
            // restores it.
            "  enabled: true",
        ),
        "security" to listOf(
            // #46: localhost/LAN URLs become reachable for web
            // extract, vision/media fetch and the browser — inside
            // the guest that is where the project's own servers live.
            "  allow_private_urls: true",
        ),
        "privacy" to listOf(
            // #47: hash user IDs and strip phone numbers from the
            // context sent to the model.
            "  redact_pii: true",
        ),
        "sessions" to listOf(
            // #50: prune ended sessions past Hermes's retention window.
            "  auto_prune: true",
        ),
        "stt" to listOf(
            // #52: live partial transcription while speaking (cloud
            // STT providers; the local whisper path ignores it).
            "  streaming: true",
        ),
        "voice" to listOf(
            // #53: on voice surfaces, replies are spoken back.
            "  auto_tts: true",
        ),
        "tool_loop_guardrails" to listOf(
            // #54: a tool loop that keeps failing is hard-stopped
            // instead of only warned about.
            "  hard_stop_enabled: true",
        ),
    )

    /**
     * The cli platform's toolset list is the `hermes-cli` base bundle
     * plus the extras the user selected in the Hermes options (see
     * [HermesConfigOptions.cliToolsets]; the full-power defaults are
     * the default-off toolsets Hermes allows on this platform).
     * `discord`/`discord_admin` are absent on purpose: Hermes
     * restricts them to discord-platform sessions and filters them out
     * of cli resolution even when named — their unlock is a Discord
     * bot token on a gateway surface (#5/#6/#24). Tools whose other
     * half is missing still self-gate through their check functions
     * (spotify needs its catalog plugin + login, kanban's worker tools
     * the dispatcher), so naming them costs nothing today and
     * activates them when the other half arrives. The `a2a` entry
     * older builds wrote is gone: hermes-agent 0.19.0 has no such
     * toolset (verified against the PyPI sdist).
     */
    private val CLI_BASE_TOOLSET = "hermes-cli"

    /**
     * Inventory #15, written ahead of the ACP bridge: the `hermes-acp`
     * composite is the coding set minus `clarify`, so it drops image
     * generation, TTS, cron, kanban and clarify; the acp platform list
     * names them explicitly. Inert until a session resolves toolsets
     * for the acp platform. `computer_use` stays out — it is
     * desktop-only (#31).
     */
    private val ACP_TOOLSETS = listOf(
        "hermes-acp", "image_gen", "tts", "cronjob", "kanban", "clarify",
        "video", "video_gen", "x_search",
    )

    /** .env names the app owns (the Hermes provider catalog's key envs); any other line is user state. */
    private val APP_ENV_KEYS: List<String> = HermesProviderCatalog.ALL_KEY_ENVS

    private val TOP_LEVEL_KEY = Regex("^([A-Za-z_][A-Za-z0-9_.-]*):")

    /** Full default config.yaml for a HERMES_HOME that has none yet. */
    fun freshConfig(
        route: HermesRoute,
        model: String,
        options: HermesConfigOptions = HermesConfigOptions.FULL_POWER,
    ): String {
        val lines = mutableListOf<String>()
        lines += modelSectionLines(route, model)
        if (route.custom) lines += customProviderSectionLines(route)
        for ((section, children) in freshSections(options)) {
            lines += "$section:"
            lines += children
        }
        ensurePlatformToolsets(lines, options)
        setToolSearch(lines, options.toolSearch)
        ensureHooksAutoAccept(lines)
        return lines.joinToString("\n", postfix = "\n")
    }

    /**
     * Merges [existing] config.yaml text per the contract above. The
     * result is stable: merging an already-merged file changes nothing.
     */
    fun mergedConfig(
        existing: String,
        route: HermesRoute,
        model: String,
        options: HermesConfigOptions = HermesConfigOptions.FULL_POWER,
    ): String {
        val lines = existing.split("\n").toMutableList()
        // Drop the artefactual empty entry a trailing newline produces;
        // the result is re-terminated below.
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        updateModelSection(lines, route, model)
        if (route.custom) updateCustomProviderSection(lines, route)
        for ((section, children) in STATIC_SECTIONS) {
            ensureSectionKeys(lines, section, children)
        }
        applyOptions(lines, options)
        ensurePlatformToolsets(lines, options)
        ensureHooksAutoAccept(lines)
        return lines.joinToString("\n", postfix = "\n")
    }

    /**
     * Merges .env text: lines carrying an app-owned key are removed and
     * the current route's secret is appended under its own env name (only
     * when a secret exists — a keyless route writes no empty assignment);
     * every other line (user-added keys, comments, blanks) survives
     * verbatim.
     */
    fun mergedEnv(existing: String, route: HermesRoute, secret: String): String {
        val lines = existing.split("\n").toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        lines.removeAll { line -> APP_ENV_KEYS.any { key -> line.trimStart().startsWith("$key=") } }
        if (secret.isNotBlank() && route.keyEnv.isNotBlank()) {
            lines += "${route.keyEnv}=$secret"
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    /**
     * The sections of a fresh config in canonical order: the static
     * unlock sections interleaved with the option-controlled keys, so a
     * fresh file reads exactly like the merge of an empty one.
     */
    private fun freshSections(options: HermesConfigOptions): List<Pair<String, List<String>>> {
        val statics = STATIC_SECTIONS.toMap()
        fun static(name: String): List<String> = statics[name].orEmpty()
        return listOf(
            "database" to static("database"),
            "terminal" to static("terminal"),
            "approvals" to listOf(
                "  mode: \"${options.approvalsMode}\"",
                "  cron_mode: \"${options.approvalsCronMode}\"",
            ),
            "updates" to static("updates"),
            "memory" to listOf(
                "  memory_enabled: ${options.memoryEnabled}",
                "  user_profile_enabled: ${options.memoryUserProfile}",
                "  write_approval: ${options.memoryWriteApproval}",
            ),
            "skills" to listOf(
                "  write_approval: ${options.skillsWriteApproval}",
            ) + static("skills"),
            "curator" to listOf(
                "  enabled: ${options.curatorEnabled}",
            ) + static("curator"),
            "delegation" to listOf(
                "  max_concurrent_children: ${options.delegationMaxChildren}",
            ) + static("delegation"),
            "agent" to listOf(
                "  coding_context: \"${options.codingContext}\"",
            ) + static("agent"),
            "checkpoints" to static("checkpoints"),
            "security" to static("security"),
            "privacy" to static("privacy"),
            "sessions" to static("sessions"),
            "stt" to static("stt"),
            "voice" to static("voice"),
            "tool_loop_guardrails" to static("tool_loop_guardrails"),
        )
    }

    /**
     * Applies the user's Hermes options to an existing config with set
     * semantics: these keys belong to the app settings (their source of
     * truth), so a value already in the file — from an older build, a
     * guest-terminal edit, or Hermes itself — is replaced, and a missing
     * section is appended whole in canonical order. Flow-style sections
     * are left untouched, the same limit as every other rewrite here.
     */
    private fun applyOptions(lines: MutableList<String>, options: HermesConfigOptions) {
        setSectionKeys(
            lines, "approvals",
            listOf(
                "  mode: \"${options.approvalsMode}\"",
                "  cron_mode: \"${options.approvalsCronMode}\"",
            ),
        )
        setSectionKeys(
            lines, "memory",
            listOf(
                "  memory_enabled: ${options.memoryEnabled}",
                "  user_profile_enabled: ${options.memoryUserProfile}",
                "  write_approval: ${options.memoryWriteApproval}",
            ),
        )
        setSectionKeys(lines, "skills", listOf("  write_approval: ${options.skillsWriteApproval}"))
        setSectionKeys(lines, "curator", listOf("  enabled: ${options.curatorEnabled}"))
        setSectionKeys(
            lines, "delegation",
            listOf("  max_concurrent_children: ${options.delegationMaxChildren}"),
        )
        setSectionKeys(lines, "agent", listOf("  coding_context: \"${options.codingContext}\""))
        setToolSearch(lines, options.toolSearch)
    }

    /**
     * Sets every child of [section] to the given rendered line (see
     * [applyOptions]): existing keys are replaced in place, missing keys
     * are appended at the end of the section in the given order.
     */
    private fun setSectionKeys(lines: MutableList<String>, section: String, children: List<String>) {
        val found = findSection(lines, section)
        when {
            found == null -> {
                lines += "$section:"
                lines += children
            }
            found.inlineValue -> Unit // Present in flow form; left to Hermes.
            else -> {
                var end = found.endIndex
                for (child in children) {
                    val indent = child.takeWhile { it == ' ' }.length
                    val key = child.trim().substringBefore(':')
                    val existing = childKeyIndex(lines, Section(found.headerIndex, end, inlineValue = false), indent, key)
                    if (existing >= 0) {
                        lines[existing] = child
                    } else {
                        lines.add(end, child)
                        end++
                    }
                }
            }
        }
    }

    private fun modelSectionLines(route: HermesRoute, model: String): List<String> = buildList {
        add("model:")
        add("  provider: ${quote(route.provider)}")
        add("  default: ${quote(model)}")
        if (!route.custom && route.baseUrl.isNotBlank()) {
            add("  base_url: ${quote(route.baseUrl)}")
        }
    }

    /**
     * The custom endpoint as a keyed `providers:` entry — the shape
     * hermes-agent 0.19.0's resolver reads (`api` is the canonical URL
     * key of this shape; `key_env` names the .env variable holding the
     * key, so the secret never lands inline in config.yaml).
     */
    private fun customProviderSectionLines(route: HermesRoute): List<String> = listOf(
        "providers:",
        "  ${route.provider}:",
        "    name: ${quote(HermesProviderCatalog.CUSTOM_PROVIDER_NAME)}",
        "    api: ${quote(route.baseUrl)}",
        "    key_env: ${route.keyEnv}",
    )

    /** Provider key older builds wrote for the custom endpoint; superseded by [HermesProviderCatalog.CUSTOM_PROVIDER_KEY]. */
    private const val LEGACY_CUSTOM_PROVIDER_KEY = "mh-custom"

    private fun updateModelSection(lines: MutableList<String>, route: HermesRoute, model: String) {
        val section = findSection(lines, "model")
        when {
            section == null -> lines.addAll(modelSectionLines(route, model))
            // Flow-style model section: left to Hermes; the launch flags route the run.
            section.inlineValue -> Unit
            else -> {
                var current = setChildLine(lines, section, indent = 2, key = "provider", "  provider: ${quote(route.provider)}")
                current = setChildLine(lines, current, indent = 2, key = "default", "  default: ${quote(model)}")
                if (!route.custom && route.baseUrl.isNotBlank()) {
                    setChildLine(lines, current, indent = 2, key = "base_url", "  base_url: ${quote(route.baseUrl)}")
                } else {
                    // App-owned field: a stale endpoint from a previous
                    // provider must not leak into the new route (Hermes
                    // would send the new provider's key to the old URL).
                    removeChildLine(lines, current, indent = 2, key = "base_url")
                }
            }
        }
    }

    private fun updateCustomProviderSection(lines: MutableList<String>, route: HermesRoute) {
        val section = findSection(lines, "providers")
        when {
            section == null -> lines.addAll(customProviderSectionLines(route))
            section.inlineValue -> Unit // Same flow-style limit as the model section.
            else -> {
                // The legacy app-owned block from older builds is removed:
                // it is this writer's own artifact, and leaving it would
                // keep a second, stale custom endpoint defined.
                removeProviderBlock(lines, LEGACY_CUSTOM_PROVIDER_KEY)
                val refreshed = findSection(lines, "providers") ?: return
                val nameIndex = childKeyIndex(lines, refreshed, indent = 2, key = route.provider)
                if (nameIndex < 0) {
                    lines.add(refreshed.headerIndex + 1, "  ${route.provider}:")
                    lines.addAll(
                        refreshed.headerIndex + 2,
                        listOf(
                            "    name: ${quote(HermesProviderCatalog.CUSTOM_PROVIDER_NAME)}",
                            "    api: ${quote(route.baseUrl)}",
                            "    key_env: ${route.keyEnv}",
                        ),
                    )
                } else {
                    // The named provider's sub-block runs to the first
                    // following line indented less than its own children.
                    var end = nameIndex + 1
                    while (end < refreshed.endIndex && (lines[end].isBlank() || lines[end].takeWhile { it == ' ' }.length >= 4)) {
                        end++
                    }
                    val subBlock = Section(nameIndex, end, inlineValue = false)
                    var current = setChildLine(lines, subBlock, indent = 4, key = "name", "    name: ${quote(HermesProviderCatalog.CUSTOM_PROVIDER_NAME)}")
                    current = setChildLine(lines, current, indent = 4, key = "api", "    api: ${quote(route.baseUrl)}")
                    // An older build's `base_url` alias line is this
                    // writer's own key; drop it so the entry carries one
                    // canonical URL.
                    current = removeChildLine(lines, current, indent = 4, key = "base_url")
                    setChildLine(lines, current, indent = 4, key = "key_env", "    key_env: ${route.keyEnv}")
                }
            }
        }
    }

    /** Removes the named provider's sub-block (header + its children) from `providers:`, if present. */
    private fun removeProviderBlock(lines: MutableList<String>, name: String) {
        val section = findSection(lines, "providers") ?: return
        if (section.inlineValue) return
        val nameIndex = childKeyIndex(lines, section, indent = 2, key = name)
        if (nameIndex < 0) return
        var end = nameIndex + 1
        while (end < section.endIndex && (lines[end].isBlank() || lines[end].takeWhile { it == ' ' }.length >= 4)) {
            end++
        }
        for (i in end - 1 downTo nameIndex) lines.removeAt(i)
    }

    /** Adds [children] of [section] that are absent; present keys keep their existing values. */
    private fun ensureSectionKeys(lines: MutableList<String>, section: String, children: List<String>) {
        val found = findSection(lines, section)
        when {
            found == null -> {
                lines += "$section:"
                lines += children
            }
            found.inlineValue -> Unit // Present in flow form; counts as configured.
            else -> {
                val missing = children.filter { child ->
                    val indent = child.takeWhile { it == ' ' }.length
                    val key = child.trim().substringBefore(':')
                    childKeyIndex(lines, found, indent, key) < 0
                }
                if (missing.isNotEmpty()) lines.addAll(found.headerIndex + 1, missing)
            }
        }
    }

    /**
     * `platform_toolsets` is app-owned policy, like the model route:
     * the app promises full power, so the cli/acp lists are rewritten
     * on every launch — as a union with what is already there, so a
     * toolset the user added by hand stays enabled. Lists for other
     * platforms (telegram, …) are user state and are never touched.
     */
    private fun ensurePlatformToolsets(lines: MutableList<String>, options: HermesConfigOptions) {
        // acp first: a missing child is inserted directly under the
        // section header, so this order leaves cli above acp.
        setPlatformToolsets(lines, "acp", ACP_TOOLSETS)
        setPlatformToolsets(lines, "cli", (listOf(CLI_BASE_TOOLSET) + options.cliToolsets).distinct())
    }

    private fun setPlatformToolsets(lines: MutableList<String>, platform: String, required: List<String>) {
        if (findSection(lines, "platform_toolsets") == null) lines += "platform_toolsets:"
        val section = findSection(lines, "platform_toolsets") ?: return
        if (section.inlineValue) return // Flow-style section: left to Hermes.
        val childIndex = childKeyIndex(lines, section, indent = 2, key = platform)
        if (childIndex < 0) {
            lines.add(section.headerIndex + 1, "  $platform: [${required.joinToString(", ")}]")
            return
        }
        // Existing names come from a flow value on the child line
        // (`cli: [a, b]`) and/or block items under it (`  - a`); both
        // shapes collapse into one flow line carrying required ∪ existing.
        val existing = mutableListOf<String>()
        val inlineValue = lines[childIndex].substringAfter(':').trim()
        var end = childIndex + 1
        if (inlineValue.startsWith("[")) {
            existing += parseFlowList(inlineValue)
        } else if (inlineValue.isEmpty()) {
            while (end < section.endIndex && BLOCK_ITEM.containsMatchIn(lines[end])) {
                existing += BLOCK_ITEM.find(lines[end])!!.groupValues[1].trim().trim('"', '\'')
                end++
            }
        }
        val merged = required + existing.filter { it !in required }
        for (i in end - 1 downTo childIndex + 1) lines.removeAt(i)
        lines[childIndex] = "  $platform: [${merged.joinToString(", ")}]"
    }

    private val BLOCK_ITEM = Regex("^\\s*-\\s+(.+)$")

    private fun parseFlowList(value: String): List<String> {
        val inner = value.substringAfter('[', "").substringBeforeLast(']', "")
        if (inner.isBlank()) return emptyList()
        return inner.split(',').map { it.trim().trim('"', '\'') }.filter(String::isNotEmpty)
    }

    /**
     * Sets `tools.tool_search.enabled` to the user's chosen mode
     * (inventory #35: with "off" every tool is eager — no search-first
     * bridge hides the default-deferred core tools or any MCP/plugin
     * tool). Unlike the unlock keys this one is option-controlled, so
     * an existing block-style value is replaced; a flow-style
     * `tool_search:` line counts as configured and is left untouched,
     * the same limit as every other rewrite here.
     */
    private fun setToolSearch(lines: MutableList<String>, mode: String) {
        val rendered = "    enabled: \"$mode\""
        if (findSection(lines, "tools") == null) {
            lines += "tools:"
            lines += "  tool_search:"
            lines += rendered
            return
        }
        val section = findSection(lines, "tools") ?: return
        if (section.inlineValue) return // Flow-style section: left to Hermes.
        val childIndex = childKeyIndex(lines, section, indent = 2, key = "tool_search")
        if (childIndex < 0) {
            lines.add(section.headerIndex + 1, "  tool_search:")
            lines.add(section.headerIndex + 2, rendered)
            return
        }
        if (lines[childIndex].substringAfter(':').trim().isNotEmpty()) return // Flow form: configured.
        // The tool_search sub-block runs to the first line indented
        // two spaces or less (or the end of the tools section).
        var end = childIndex + 1
        while (end < section.endIndex &&
            (lines[end].isBlank() || lines[end].takeWhile { it == ' ' }.length > 2)
        ) {
            end++
        }
        val block = Section(childIndex, end, inlineValue = false)
        setChildLine(lines, block, indent = 4, key = "enabled", rendered)
    }

    /**
     * Inventory #48: the `hooks` table itself is user/Hermes state
     * and is never written, but hooks registered there are only picked
     * up in non-interactive runs with an auto-accept path — the
     * top-level config key (the launch env sets HERMES_ACCEPT_HOOKS
     * as well). Ensure-if-absent, like every unlock key.
     */
    private fun ensureHooksAutoAccept(lines: MutableList<String>) {
        val present = lines.any { line ->
            TOP_LEVEL_KEY.find(line)?.groupValues?.get(1) == "hooks_auto_accept"
        }
        if (!present) lines += "hooks_auto_accept: true"
    }

    /** A top-level section: header line index, exclusive end index, and whether it carries an inline (flow) value. */
    private data class Section(val headerIndex: Int, val endIndex: Int, val inlineValue: Boolean)

    private fun findSection(lines: List<String>, key: String): Section? {
        val header = lines.indexOfFirst { line ->
            TOP_LEVEL_KEY.find(line)?.groupValues?.get(1) == key
        }
        if (header < 0) return null
        var end = header + 1
        while (end < lines.size && !TOP_LEVEL_KEY.containsMatchIn(lines[end])) end++
        val afterColon = lines[header].substringAfter(':').trim()
        return Section(header, end, inlineValue = afterColon.isNotEmpty() && !afterColon.startsWith("#"))
    }

    /** Index of the `key:` line at exactly [indent] spaces inside [section], or -1. */
    private fun childKeyIndex(lines: List<String>, section: Section, indent: Int, key: String): Int {
        val pattern = Regex("^ {$indent}$key\\s*:")
        for (i in (section.headerIndex + 1) until section.endIndex) {
            if (pattern.containsMatchIn(lines[i])) return i
        }
        return -1
    }

    /** Replaces the child line for [key] or inserts [rendered] directly under the section header. */
    private fun setChildLine(
        lines: MutableList<String>,
        section: Section,
        indent: Int,
        key: String,
        rendered: String,
    ): Section {
        val existing = childKeyIndex(lines, section, indent, key)
        if (existing >= 0) {
            lines[existing] = rendered
            return section
        }
        lines.add(section.headerIndex + 1, rendered)
        return section.copy(endIndex = section.endIndex + 1)
    }

    private fun removeChildLine(lines: MutableList<String>, section: Section, indent: Int, key: String): Section {
        val existing = childKeyIndex(lines, section, indent, key)
        if (existing < 0) return section
        lines.removeAt(existing)
        return section.copy(endIndex = section.endIndex - 1)
    }

    private fun quote(value: String): String = "'${value.replace("'", "''")}'"
}

/**
 * The guest environment every Hermes launch runs with: HERMES_HOME,
 * the route's provider key, and three presence/unlock markers.
 *
 *  - `HERMES_INTERACTIVE=1` (inventory #19): `cronjob_manage`'s
 *    availability check requires one of the session-presence vars,
 *    and this is the one Hermes's own CLI sets for its chat sessions
 *    (`cli.py`, for interactive sudo prompts). Its side effects are
 *    inert in this bridge: the approval gate explicitly clears the
 *    whole presence trio for single-query sessions
 *    (`tools/approval.py::_presence`), so approvals still resolve
 *    from the config policy, and the guest runs as root, so the sudo
 *    prompts it enables never fire. The alternatives are worse:
 *    `HERMES_GATEWAY_SESSION` would misclassify the session as
 *    gateway-owned (approval prompts routed to a listener that does
 *    not exist), `HERMES_CRON_SESSION` would switch approval
 *    handling to `cron_mode` (default: deny) and file writes to
 *    unattended handling, and `HERMES_EXEC_ASK` is the marker a
 *    gateway sets at startup for its exec-approval bridge. Job
 *    *firing* still needs the gateway — this only makes the tool
 *    visible and usable.
 *  - `HERMES_ACCEPT_HOOKS=1` (inventory #86): shell hooks Hermes or
 *    the user registers are picked up without a TTY consent prompt.
 *  - `HERMES_ENABLE_PROJECT_PLUGINS=1` (inventory #87): plugins in
 *    the workspace's `.hermes/plugins/` load. The workspace is the
 *    user's own project, and the agent already runs an ungated
 *    terminal there, so this grants no new privilege.
 */
internal fun hermesLaunchEnvironment(route: HermesRoute, secret: String): Map<String, String> = linkedMapOf(
    "HERMES_HOME" to HermesRuntimeBridge.HERMES_HOME_GUEST_PATH,
    route.keyEnv to secret,
    "HERMES_INTERACTIVE" to "1",
    "HERMES_ACCEPT_HOOKS" to "1",
    "HERMES_ENABLE_PROJECT_PLUGINS" to "1",
)

/**
 * [RuntimeBridge] driving Nous Research's Hermes Agent in one-shot mode:
 * `hermes chat -q <prompt> -Q` per message (Hermes 0.19.0's quiet
 * automation transport — see [HermesQuietOutput] for why this replaced
 * the stream-json launch and for the verified output contract).
 *
 * The context prompt travels as a single argv entry: the launcher spawns
 * an argv array (never a shell), so the only limit is the kernel's
 * 128 KiB per-argument exec cap, which [buildHermesPrompt] stays under
 * by shedding the oldest history first. Continuity across messages uses
 * Hermes's own session store: the run prints its real session id in a
 * `session_id:` trailer, the bridge persists it per project chat, and
 * later messages pass it back with `--resume`.
 *
 * Approvals are config-only in this mode: the generated config.yaml sets a
 * deterministic one-shot approval policy and the run is launched with
 * `--yolo`. There is no interactive approval channel until the ACP phase.
 */
class HermesRuntimeBridge(
    private val context: Context,
    private val hermesSettings: () -> HermesRuntimeSettings,
    private val conversationId: (String) -> String?,
    private val saveConversationId: (String, String) -> Unit,
    private val hostConfig: () -> HermesHostConfig = { HermesHostConfig(remote = false, baseUrl = "", apiKey = "") },
    private val remoteConversationId: (String) -> String? = { null },
    private val saveRemoteConversationId: (String, String) -> Unit = { _, _ -> },
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeGatewayClient: HermesGatewayClient? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false

    override suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress(R.string.rt_bridge_starting, "Hermes Agent")
        // Phase 4: in remote mode the turn goes to the user's Hermes gateway
        // host instead of the embedded copy. The embedded path below is
        // untouched — no local install, provider key or workspace is needed
        // for a remote turn, because the host owns all three.
        // Hermes runs from its own profile in the shared profile system
        // (Agent → Hermes), exactly like the other agents: the route,
        // model and key were all resolved from the active Hermes profile
        // by the settings lambda (see HermesProfileRoute).
        val settings = hermesSettings()
        val host = hostConfig()
        if (host.remote) {
            runCatching { runRemoteTurn(sessionId, projectId, prompt, settings.model, host) }
                .onFailure { error ->
                    Log.e("HermesBridge", "Remote Hermes session failed", error)
                    emitFailureOnce(sessionId, friendlyError(error))
                }
            activeSessionId = null
            RuntimeTaskController.stopAction = null
            return@withContext sessionId
        }
        val route = settings.route
        // A custom endpoint may legitimately run keyless (local servers);
        // Hermes's own custom-provider resolution sends its literal
        // "no-key-required" placeholder in that case. A first-class
        // provider without a key can only fail at the API, so fail here
        // with the way out instead.
        val secret = settings.secret.ifBlank {
            if (route.custom) "no-key-required" else ""
        }
        if (secret.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "No API key is saved for Hermes Agent. Open Agent → Hermes and add the provider's API key.",
                ),
            )
            return@withContext sessionId
        }
        val model = settings.model
        if (model.isBlank()) {
            eventBus.emit(
                RuntimeEvent.SessionFailed(
                    sessionId,
                    "No model is set for Hermes Agent. Open Agent → Hermes and set a model, then try again.",
                ),
            )
            return@withContext sessionId
        }

        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread {
                        running.destroy()
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }.start()
                }
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) {
                "Hermes Agent is not installed. Open Settings → Coding agent to install it."
            }
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            writeHermesConfig(installed.rootfs, route, model, secret, settings.options)
            val environment = hermesLaunchEnvironment(route, secret)
            val guestWorkspacePath = "/workspace/$projectSlug"
            // Hermes 0.19.0's one-shot transport is `-q` (the prompt travels
            // as one argv entry — the launcher spawns an argv array, never a
            // shell, so no quoting hazard) in quiet mode `-Q`. The prompt is
            // byte-capped below the kernel's 128 KiB per-argument exec limit;
            // see buildHermesPrompt.
            val resumeId = conversationId(projectId)?.takeIf(String::isNotBlank)
            val contextPrompt = buildHermesPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind, resumed = resumeId != null)
            val command = buildList {
                add(RuntimeInstaller.HERMES_GUEST_PATH)
                add("chat")
                add("-q")
                add(contextPrompt)
                add("-Q")
                add("--provider")
                add(route.provider)
                add("-m")
                add(model)
                add("-t")
                add(HERMES_TOOLSETS)
                add("--yolo")
                if (resumeId != null) {
                    add("--resume")
                    add(resumeId)
                    add("--no-restore-cwd")
                }
            }
            Log.d("HermesBridge", "Route: ${route.provider}, Model: $model, Resume: ${resumeId != null}")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
                // Hermes saves files and its session database through atomic
                // renames; PRoot's hard-link emulation would dangle them.
                emulateHardLinks = false,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()
            pushForegroundProgress(R.string.rt_bridge_working, "Hermes Agent")
            val nativeProcess = process as? NativeSpawnProcess
                ?: error("Unsupported Android runtime process")
            var outputOffset = 0L
            val pendingOutput = StringBuilder()
            val output = HermesQuietOutput.Collector()
            var providerError: String? = null

            fun handleLine(line: String) {
                if (providerError == null && line.isNotBlank()) {
                    providerError = ProviderRuntimeErrorDetector.detect(line)
                }
                output.accept(line)
            }

            // Quiet mode prints nothing until the turn ends, so this loop
            // only drains the merged capture into the classifier; the
            // outcome is decided from the process exit code below.
            while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
                val available = nativeProcess.outputFile.length() - outputOffset
                if (available <= 0) {
                    delay(50)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                    file.seek(outputOffset)
                    file.read(bytes)
                }
                if (count <= 0) continue
                outputOffset += count
                pendingOutput.append(bytes.decodeToString(0, count))
                var newline = pendingOutput.indexOf("\n")
                while (newline >= 0) {
                    val line = pendingOutput.substring(0, newline).trimEnd('\r')
                    pendingOutput.delete(0, newline + 1)
                    handleLine(line)
                    newline = pendingOutput.indexOf("\n")
                }
            }
            pendingOutput.toString().takeIf(String::isNotEmpty)?.let { handleLine(it) }
            val exit = process.waitFor()
            Log.d("HermesBridge", "Hermes process exited with code $exit")
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                Log.d("HermesBridge", "Changed files: $changed")
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                acceptLastChanges(projectId)
            }
            // Quiet mode's outcome contract: the process exit code decides
            // (0 = turn completed, 1 = turn/credentials/init failed, 130 =
            // interrupted), the `session_id:` trailer carries the real
            // session id, and a failed turn leaves its explanation in the
            // output — HermesQuietOutput.failureMessage surfaces it, so a
            // failure never again degrades to a bare exit code.
            val outcome = output.finish()
            if (!outcome.sessionId.isNullOrBlank()) {
                saveConversationId(projectId, outcome.sessionId)
            }
            when {
                userStopRequested -> throw HermesSessionException("Stopped by user")
                exit == 0 -> {
                    if (outcome.responseText.isNotBlank()) {
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, outcome.responseText))
                    }
                    emitCompletedOnce(sessionId)
                }
                else -> {
                    // A session Hermes no longer knows fails every resume;
                    // forget the stored id so the next message starts fresh.
                    if (resumeId != null) saveConversationId(projectId, "")
                    error(HermesQuietOutput.failureMessage(outcome, exit, providerError))
                }
            }
        }.onFailure { error ->
            Log.e("HermesBridge", "Session failed", error)
            emitFailureOnce(sessionId, friendlyError(error))
        }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    /**
     * One chat turn against the remote Hermes gateway. The [prompt] arrives
     * exactly as the embedded path receives it — MainViewModel has already
     * appended the GenUI capability block — and is sent as the turn's single
     * user message; the gateway keeps the conversation server-side under a
     * session id this bridge mints per project chat (`mh-<uuid>`, persisted
     * through [remoteConversationId]/[saveRemoteConversationId], separate
     * from the embedded session store so the two backends never hand each
     * other foreign ids). Tool and status frames render through the same
     * RuntimeEvents the embedded stream produces, so chat UI, activity feed
     * and GenUI rendering are identical. Host-side file changes are not
     * checkpointed: the workspace they touch is the host's, not the phone's.
     */
    private suspend fun runRemoteTurn(
        sessionId: String,
        projectId: String,
        prompt: String,
        model: String,
        host: HermesHostConfig,
    ) {
        if (host.baseUrl.isBlank()) throw HermesSessionException(HermesRemoteMessages.NO_URL)
        if (host.apiKey.isBlank()) throw HermesSessionException(HermesRemoteMessages.NO_KEY)
        startForegroundRuntime(activeProjectSlug ?: "")
        pushForegroundProgress(R.string.rt_bridge_working, "Hermes Agent")
        val client = HermesGatewayClient()
        activeGatewayClient = client
        RuntimeTaskController.stopAction = {
            userStopRequested = true
            client.disconnect()
        }
        try {
            val gatewaySessionId = remoteConversationId(projectId)?.takeIf(String::isNotBlank)
                ?: "mh-${UUID.randomUUID()}".also { minted -> saveRemoteConversationId(projectId, minted) }
            val outcome = client.streamChat(
                config = host,
                model = model,
                prompt = prompt,
                sessionId = gatewaySessionId,
                isStopped = { userStopRequested },
                onWaking = { pushForegroundProgress(HOST_WAKING_PROGRESS) },
                onEvent = { event ->
                    when (event) {
                        is HermesGatewayEvent.TextDelta -> {
                            pushForegroundProgress(R.string.rt_bridge_working, "Hermes Agent")
                            eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.text))
                        }
                        is HermesGatewayEvent.ToolProgress -> {
                            val displayName = hermesToolDisplayName(event.tool.ifBlank { "Tool" })
                            if (event.status == "running") {
                                pushForegroundProgress(
                                    AppStrings.get(context, R.string.rt_bridge_running_tool, displayName, event.label),
                                )
                                eventBus.emit(
                                    RuntimeEvent.ToolStarted(
                                        sessionId,
                                        displayName,
                                        event.label.ifBlank { "Working on the host" },
                                    ),
                                )
                            } else if (event.status == "completed") {
                                // The gateway sends no result text for a
                                // completed tool; use the placeholder the UI
                                // already localizes for empty summaries.
                                eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, displayName, "Completed successfully"))
                            }
                        }
                        is HermesGatewayEvent.Status -> pushForegroundProgress(event.text)
                        else -> Unit // Reasoning deltas and approval requests have no remote UI yet.
                    }
                },
            )
            if (userStopRequested) throw HermesSessionException("Stopped by user")
            when (outcome) {
                is HermesGatewayOutcome.Completed -> {
                    // The gateway echoes the live session id in a response
                    // header (it can rotate under compression); persist
                    // whatever it reports so the next turn continues there.
                    outcome.sessionId?.takeIf(String::isNotBlank)?.let { saveRemoteConversationId(projectId, it) }
                    emitCompletedOnce(sessionId)
                }
                is HermesGatewayOutcome.Failed -> throw HermesSessionException(outcome.message)
            }
        } finally {
            if (activeGatewayClient === client) activeGatewayClient = null
        }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // One-shot runs expose no approval channel; the generated config.yaml
        // carries the deterministic approval policy for this mode. Interactive
        // approvals arrive with the ACP driver in a later phase.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeGatewayClient?.disconnect()
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory || !File(checkpoint, "changes.json").isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) return@withContext false
        paths.forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else if (target.isFile) {
            target.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    /**
     * Brings the per-app HERMES_HOME configuration in line with the saved
     * provider profile. A missing `config.yaml` gets the full default
     * (provider mapping, full-power sections, safety settings); an
     * existing one is merged by [HermesConfigFiles] so Hermes's own
     * changes and user edits survive every launch. `.env` is merged the
     * same way, so unrelated keys a user added in the guest terminal
     * (web-search keys and the like) survive.
     */
    private fun writeHermesConfig(
        rootfs: File,
        route: HermesRoute,
        model: String,
        secret: String,
        options: HermesConfigOptions,
    ) {
        val home = File(rootfs, HERMES_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
        // Hermes's learned skills and persistent memories live under
        // HERMES_HOME; the bridge never deletes or resets them — it only
        // makes sure the directories exist.
        File(home, "skills").mkdirs()
        File(home, "memories").mkdirs()
        val configFile = File(home, "config.yaml")
        val existingConfig = configFile.takeIf(File::isFile)?.readText()
        configFile.writeText(
            if (existingConfig.isNullOrBlank()) {
                HermesConfigFiles.freshConfig(route, model, options)
            } else {
                HermesConfigFiles.mergedConfig(existingConfig, route, model, options)
            },
        )
        val envFile = File(home, ".env")
        val existingEnv = envFile.takeIf(File::isFile)?.readText().orEmpty()
        envFile.writeText(HermesConfigFiles.mergedEnv(existingEnv, route, secret))
    }

    /**
     * Assembles the context prompt for the `-q` argv transport. The shared
     * history budget allows 160 K characters, but a single argv entry is
     * capped by the kernel at 128 KiB (including the NUL), so the oldest
     * history is shed until the assembled prompt fits the byte budget;
     * the final hard truncation only fires for a pathological current
     * message and never splits a UTF-8 sequence.
     *
     * On a resumed session ([resumed]) the history embed is skipped
     * entirely: Hermes's own session store already holds the prior turns
     * (that is what `--resume` restores), so re-embedding them would make
     * the model process every prior turn twice — in fusion mode, once
     * per panel model plus the judge. The full embed remains for fresh
     * sessions: the first message of a chat, and the first message after
     * a failed resume cleared the stored id (see the failure path in
     * [startSession]), so continuity never depends on the embed alone.
     */
    private fun buildHermesPrompt(currentPrompt: String, history: List<ChatMessage>, guestWorkspacePath: String, projectKind: ProjectKind, resumed: Boolean = false): String {
        var remaining = if (resumed) emptyList() else history
        var assembled = buildContextPrompt(currentPrompt, remaining, guestWorkspacePath, projectKind)
        while (utf8ByteCount(assembled) > MAX_HERMES_PROMPT_BYTES && remaining.size > 1) {
            remaining = remaining.drop(1)
            assembled = buildContextPrompt(currentPrompt, remaining, guestWorkspacePath, projectKind)
        }
        return truncateToUtf8Bytes(assembled, MAX_HERMES_PROMPT_BYTES)
    }

    private fun buildContextPrompt(currentPrompt: String, history: List<ChatMessage>, guestWorkspacePath: String, projectKind: ProjectKind): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                !msg.text.startsWith("Failed to") &&
                !msg.text.startsWith("Error:") &&
                !msg.text.contains("API Error")
            }
            .dropLast(1)
            .recentWithinCharacterBudget(MAX_CONVERSATION_HISTORY_CHARACTERS)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        if (installer.isStackInstalled(DevStack.ANDROID)) {
            sb.appendLine("If this is an Android project, the phone already provides JDK 17, Android SDK 36, ARM64 Build Tools 35.0.0, Gradle 8.14.3, and an offline Maven repository.")
            sb.appendLine("For newly created Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 so the preinstalled offline toolchain can build immediately.")
            sb.appendLine("The bundled Maven cache handles the base toolchain; Gradle may download project-specific libraries normally. Set android.useAndroidX=true for AndroidX or Compose projects.")
            sb.appendLine("PocketDev globally configures Gradle to use the SDK's ARM64 aapt2. Do not use the x86_64 Maven aapt2, investigate its architecture, or add android.aapt2FromMavenOverride to the project.")
            sb.appendLine("Use the installed `gradle` command for Android builds; do not ask the user to install Android Studio, an SDK, Gradle, ADB, or Termux.")
        } else {
            sb.appendLine("The optional Android build toolchain is not installed in this PocketDev runtime. You may create Android project files, but do not claim that Gradle, the Android SDK, or aapt2 is available and do not present build or install commands as verified. Tell the user to add the Android development stack in PocketDev Settings before building.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    /** Localized variant; resolves the string only when the throttle allows an update. */
    private fun pushForegroundProgress(@androidx.annotation.StringRes id: Int, vararg args: Any) {
        if (activeSessionId == null) return
        if (android.os.SystemClock.elapsedRealtime() - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        pushForegroundProgress(AppStrings.get(context, id, *args))
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(
                        if (completed) RuntimeExecutionService.ACTION_COMPLETE
                        else RuntimeExecutionService.ACTION_FAILED,
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w("HermesBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: AppStrings.get(context, R.string.rt_exec_default_project),
                detail = AppStrings.get(context, R.string.rt_bridge_finished, "Hermes Agent"),
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: AppStrings.get(context, R.string.rt_exec_default_project),
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is HermesSessionException && message.isNotBlank() && !message.equals("null", ignoreCase = true) -> message
            message.contains("authentication", true) ||
                message.contains("invalid api key", true) ||
                message.contains("autherror", true) ||
                message.contains("expired", true) ||
                message.contains("quota", true) ||
                message.contains("rate limit", true) ||
                listOf("401", "403", "429").any { code ->
                    message.contains(code) && (message.contains("auth", true) || message.contains("HTTP", true))
                } ->
                "The provider rejected the saved API key."
            message.contains("missing_credential", true) ->
                "No API key reached Hermes Agent. Re-save the provider key in Settings."
            message.contains("not installed", true) -> message.take(300)
            message.isBlank() || message.equals("null", ignoreCase = true) -> "Hermes Agent could not start."
            else -> message.take(500)
        }
    }

    private class HermesSessionException(message: String) : IllegalStateException(message)

    companion object {
        const val HERMES_HOME_GUEST_PATH = "/root/.hermes"
        private const val HOST_WAKING_PROGRESS = "Waking the Hermes host…"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
        /** Byte budget for the `-q` prompt: under the kernel's 128 KiB per-argument exec cap, with headroom. */
        private const val MAX_HERMES_PROMPT_BYTES = 120_000
        /**
         * Full power, no trimming: "all" is Hermes's own maximal selector —
         * `resolve_toolset` spans every toolset except the ones gated to
         * another session platform (the desktop-only `onboarding`/`catalog`
         * sets), and it picks up toolsets future Hermes versions add
         * automatically. Individual tools still self-gate on credentials
         * and environment through their check functions (browser needs its
         * CDP backend, `x_search` needs xAI credentials, kanban needs the
         * kanban dispatcher), so enabling everything costs nothing where a
         * capability cannot run. One exception is Hermes-internal:
         * one-shot sessions always lose the `clarify` toolset
         * (`agent/oneshot_footprint.py`), because nobody is present to
         * answer it — no flag or config restores it in this mode.
         */
        private const val HERMES_TOOLSETS = "all"
    }
}

/** Collapses whitespace while inspecting only a bounded tail of untrusted agent output. */
internal fun compactToolText(value: String, rawLimit: Int, outputLimit: Int): String {
    if (value.isEmpty() || rawLimit <= 0 || outputLimit <= 0) return ""
    val start = (value.length - rawLimit).coerceAtLeast(0)
    val result = StringBuilder(value.length - start)
    var pendingSpace = false
    for (index in start until value.length) {
        val char = value[index]
        if (char.isWhitespace()) {
            pendingSpace = result.isNotEmpty()
        } else {
            if (pendingSpace) result.append(' ')
            pendingSpace = false
            result.append(char)
        }
    }
    return result.toString().takeLast(outputLimit)
}
