package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v21 cross-agent transcripts: stable guest paths and a plain-markdown
 * rendering a sister agent can read with its normal file tools.
 */
class AgentTranscriptsTest {

    @Test
    fun guestPathsAreStablePerProjectAndAgent() {
        assertEquals(
            "/workspace/.agent-chats/nimble-turing/hermes.md",
            AgentTranscripts.guestTranscriptPath("nimble-turing", AgentKind.HERMES),
        )
        assertEquals(
            "/workspace/.agent-chats/nimble-turing/claude-code.md",
            AgentTranscripts.guestTranscriptPath("nimble-turing", AgentKind.CLAUDE_CODE),
        )
        assertEquals(
            "/workspace/.agent-chats/nimble-turing/antigravity.md",
            AgentTranscripts.guestTranscriptPath("nimble-turing", AgentKind.ANTIGRAVITY),
        )
        assertEquals(
            "/workspace/.agent-chats/nimble-turing/team-notes.md",
            AgentTranscripts.guestTeamNotesPath("nimble-turing"),
        )
    }

    @Test
    fun renderIncludesChatsMessagesAndSteps() {
        val text = AgentTranscripts.renderTranscript(
            projectName = "Nimble Turing",
            agent = AgentKind.HERMES,
            sections = listOf(
                AgentTranscripts.Section(
                    title = "Fix the build",
                    entries = listOf(
                        AgentTranscripts.Entry(fromUser = true, text = "Please fix the build"),
                        AgentTranscripts.Entry(
                            fromUser = false,
                            text = "Fixed — the SDK path was wrong.",
                            workTitles = listOf("Ran gradle", "Edited settings.gradle"),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(text.contains("# Hermes — chats in project \"Nimble Turing\""))
        assertTrue(text.contains("## Chat: Fix the build"))
        assertTrue(text.contains("**User:** Please fix the build"))
        assertTrue(text.contains("**Hermes:** Fixed — the SDK path was wrong."))
        assertTrue(text.contains("**Hermes (steps):** Ran gradle; Edited settings.gradle"))
        assertTrue(text.contains("team-notes.md"))
    }

    @Test
    fun renderHandlesAnAgentWithNoChatsYet() {
        val text = AgentTranscripts.renderTranscript("P", AgentKind.ANTIGRAVITY, emptyList())
        assertTrue(text.contains("# Antigravity — chats in project \"P\""))
        assertTrue(text.contains("_No chats yet._"))
    }

    @Test
    fun teamNotesTemplateExplainsAppending() {
        val text = AgentTranscripts.teamNotesTemplate("Nimble Turing")
        assertTrue(text.contains("# Team notes — project \"Nimble Turing\""))
        assertTrue(text.contains("Append only"))
        assertTrue(text.contains("Hermes, Claude Code, Antigravity"))
    }
}
