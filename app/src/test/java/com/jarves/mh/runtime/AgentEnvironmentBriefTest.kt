package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.shortName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v21 environment brief: every agent must be told, every turn, what app it
 * runs in, that the whole guest filesystem is reachable, that each agent
 * has its own chat in the project, and where the sister transcripts and
 * team notes live — without duplicating the generative-ui contract.
 */
class AgentEnvironmentBriefTest {

    @Test
    fun briefNamesTheAgentTheAppAndTheProject() {
        val brief = AgentEnvironmentBrief.build(AgentKind.CLAUDE_CODE, "Nimble Turing", "nimble-turing")
        assertTrue(brief.contains("You are Claude Code"))
        assertTrue(brief.contains("\"Hermes Agent\" Android app"))
        assertTrue(brief.contains("\"Nimble Turing\": /workspace/nimble-turing"))
        assertTrue(brief.contains("not confined to the project folder"))
    }

    @Test
    fun briefIsPersonalisedPerAgent() {
        AgentKind.entries.forEach { agent ->
            val brief = AgentEnvironmentBrief.build(agent, "P", "p")
            assertTrue(brief.contains("You are ${agent.shortName}"))
            assertTrue(brief.contains("you are ${agent.shortName}"))
        }
    }

    @Test
    fun briefPointsAtEverySisterTranscriptAndTheTeamNotes() {
        val brief = AgentEnvironmentBrief.build(AgentKind.HERMES, "P", "my-proj")
        assertTrue(brief.contains("/workspace/.agent-chats/my-proj/hermes.md"))
        assertTrue(brief.contains("/workspace/.agent-chats/my-proj/claude-code.md"))
        assertTrue(brief.contains("/workspace/.agent-chats/my-proj/antigravity.md"))
        assertTrue(brief.contains("/workspace/.agent-chats/my-proj/team-notes.md"))
        assertTrue(brief.contains("OWN separate chat"))
        assertTrue(brief.contains("sister agents"))
    }

    @Test
    fun briefDoesNotDuplicateTheGenerativeUiContract() {
        val brief = AgentEnvironmentBrief.build(AgentKind.ANTIGRAVITY, "P", "p")
        // The contract block follows the brief in the prompt; the brief must
        // only reference it, never restate component/action vocabularies.
        assertFalse(brief.contains("approval_card"))
        assertFalse(brief.contains("\"protocol\""))
        assertFalse(brief.contains("radio_group"))
        assertTrue(brief.contains("generative-ui protocol described below"))
    }
}
