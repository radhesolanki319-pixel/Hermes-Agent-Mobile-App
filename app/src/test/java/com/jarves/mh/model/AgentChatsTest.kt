package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v21 per-agent chats: the (project, agent) keying rests on ProjectChat
 * carrying its owning agent, and on the one-time migration that assigns a
 * project's pre-v21 chats — written when all agents shared one chat list —
 * to the agent that was active for the project at upgrade time.
 */
class AgentChatsTest {

    private fun chat(id: String, agent: AgentKind) = ProjectChat(
        id = id,
        title = "Chat $id",
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
        agentKind = agent,
    )

    @Test
    fun legacyChatsAreAssignedToTheFallbackAgent() {
        val chats = listOf(
            chat("a", AgentKind.HERMES),
            chat("b", AgentKind.HERMES),
        )
        val migrated = chats.assigningAgentToLegacyChats(setOf("a", "b"), AgentKind.CLAUDE_CODE)
        assertEquals(listOf(AgentKind.CLAUDE_CODE, AgentKind.CLAUDE_CODE), migrated.map { it.agentKind })
        // Nothing else about the chats changes.
        assertEquals(listOf("a", "b"), migrated.map { it.id })
        assertEquals(listOf("Chat a", "Chat b"), migrated.map { it.title })
    }

    @Test
    fun chatsWithAStoredAgentAreNeverReassigned() {
        val chats = listOf(
            chat("a", AgentKind.ANTIGRAVITY),
            chat("b", AgentKind.HERMES),
        )
        // Only "b" is legacy; "a" already belongs to Antigravity.
        val migrated = chats.assigningAgentToLegacyChats(setOf("b"), AgentKind.CLAUDE_CODE)
        assertEquals(AgentKind.ANTIGRAVITY, migrated[0].agentKind)
        assertEquals(AgentKind.CLAUDE_CODE, migrated[1].agentKind)
    }

    @Test
    fun noLegacyChatsReturnsTheListUntouched() {
        val chats = listOf(chat("a", AgentKind.HERMES))
        assertSame(chats, chats.assigningAgentToLegacyChats(emptySet(), AgentKind.CLAUDE_CODE))
    }

    @Test
    fun shortNamesAreTheHumanAgentNames() {
        assertEquals("Hermes", AgentKind.HERMES.shortName)
        assertEquals("Claude Code", AgentKind.CLAUDE_CODE.shortName)
        assertEquals("Antigravity", AgentKind.ANTIGRAVITY.shortName)
    }
}
