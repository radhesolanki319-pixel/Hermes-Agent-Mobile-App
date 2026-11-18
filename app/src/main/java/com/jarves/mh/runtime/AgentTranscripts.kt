package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.shortName

/**
 * Cross-agent chat transcripts (v21). Each agent has its own chat per
 * project inside the app; so the three agents can still see and build on
 * each other's work, the app maintains a plain-markdown copy of every
 * agent's chats in the guest filesystem at
 * `/workspace/.agent-chats/<project-slug>/<agent>.md`, regenerated at turn
 * boundaries, plus a shared `team-notes.md` the agents append to with
 * their normal file tools.
 *
 * This object is pure (paths + markdown rendering) so it is unit-testable;
 * MainViewModel owns the actual file writes on the host side, and
 * RuntimeInstaller bind-mounts the host directory into the guest at
 * [GUEST_ROOT] so every agent session can read and append.
 */
object AgentTranscripts {

    const val GUEST_ROOT = "/workspace/.agent-chats"
    const val TEAM_NOTES_FILE = "team-notes.md"

    /** Guest directory holding one project's transcripts and team notes. */
    fun guestDir(projectSlug: String): String = "$GUEST_ROOT/$projectSlug"

    fun guestTranscriptPath(projectSlug: String, agent: AgentKind): String =
        "${guestDir(projectSlug)}/${agent.stableId}.md"

    fun guestTeamNotesPath(projectSlug: String): String =
        "${guestDir(projectSlug)}/$TEAM_NOTES_FILE"

    /** One rendered message: who said it, the text, and any step titles worth keeping. */
    data class Entry(
        val fromUser: Boolean,
        val text: String,
        val workTitles: List<String> = emptyList(),
    )

    /** One chat of the agent in the project (an agent may have several). */
    data class Section(
        val title: String,
        val entries: List<Entry>,
    )

    fun renderTranscript(projectName: String, agent: AgentKind, sections: List<Section>): String {
        val name = agent.shortName
        val sb = StringBuilder()
        sb.appendLine("# $name — chats in project \"$projectName\"")
        sb.appendLine()
        sb.appendLine(
            "Readable copy of $name's own chat(s) with the user in this project, maintained by the app. " +
                "The sister agents' transcripts (hermes.md, claude-code.md, antigravity.md) sit in the same folder; " +
                "shared handoff notes live in team-notes.md.",
        )
        if (sections.isEmpty()) {
            sb.appendLine()
            sb.appendLine("_No chats yet._")
        }
        sections.forEach { section ->
            sb.appendLine()
            sb.appendLine("## Chat: ${section.title}")
            section.entries.forEach { entry ->
                sb.appendLine()
                if (entry.fromUser) {
                    sb.appendLine("**User:** ${entry.text}")
                } else {
                    if (entry.text.isNotBlank()) sb.appendLine("**$name:** ${entry.text}")
                    if (entry.workTitles.isNotEmpty()) {
                        sb.appendLine("**$name (steps):** ${entry.workTitles.joinToString("; ")}")
                    }
                    if (entry.text.isBlank() && entry.workTitles.isEmpty()) {
                        sb.appendLine("**$name:** _(no text)_")
                    }
                }
            }
        }
        return sb.toString()
    }

    /** Initial content for a project's team-notes.md; created once, never overwritten afterwards. */
    fun teamNotesTemplate(projectName: String): String = """
        # Team notes — project "$projectName"

        Shared scratchpad for the three agents working in this project (Hermes, Claude Code, Antigravity).
        Check this file at the start of substantial tasks. When you finish work another agent may
        continue, append a short handoff note: what you did, what state things are in, what is next.
        Append only — do not rewrite or delete other agents' notes.

    """.trimIndent()
}
