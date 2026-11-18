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

    // v29: the remote variant. A Hermes turn on the gateway host must be
    // told the truth about the HOST — the phone brief sent it hunting for
    // /workspace, sister transcripts and team notes that do not exist
    // there, and it wrote long replies reporting them missing.

    @Test
    fun remoteBriefStatesTheHostTruths() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("Swift Bose")
        assertTrue(brief.contains("<app_environment>"))
        assertTrue(brief.contains("</app_environment>"))
        assertTrue(brief.contains("You are Hermes"))
        assertTrue(brief.contains("your own always-on computer"))
        assertTrue(brief.contains("cloud host"))
        assertTrue(brief.contains("\"Hermes Agent\" Android app"))
        assertTrue(brief.contains("connects to this computer over the internet"))
        assertTrue(brief.contains("generative-ui protocol described below"))
        assertTrue(brief.contains("HERMES_HOME"))
        assertTrue(brief.contains("may create your own workspace folders on this computer"))
    }

    @Test
    fun remoteBriefTreatsTheProjectNameAsAnAppLabelOnly() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("Swift Bose")
        assertTrue(brief.contains("\"Swift Bose\""))
        assertTrue(brief.contains("only the app's label for this conversation"))
        assertTrue(brief.contains("NOT present on this computer"))
        assertTrue(brief.contains("never report them missing"))
        assertTrue(brief.contains("not visible from this computer"))
        assertTrue(brief.contains("do not go searching for them"))
    }

    @Test
    fun remoteBriefPlacesTheSisterAgentsOnThePhone() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("P")
        assertTrue(brief.contains("Claude Code and Antigravity"))
        assertTrue(brief.contains("run on the user's phone, not on this computer"))
        assertTrue(brief.contains("their chats are not readable from here"))
    }

    @Test
    fun remoteBriefCarriesNoPhoneEnvironmentClaims() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("Swift Bose")
        // No phone-side paths, no transcript/team-notes instructions, no
        // PRoot/phone claims — nothing for Hermes to go looking for.
        assertFalse(brief.contains("/workspace"))
        assertFalse(brief.contains("team-notes"))
        assertFalse(brief.contains(".agent-chats"))
        assertFalse(brief.contains("PRoot"))
        assertFalse(brief.contains("running inside the \"Hermes Agent\" Android app"))
        assertFalse(brief.contains("Your working directory is"))
        assertFalse(brief.contains("separate chat"))
    }

    @Test
    fun remoteBriefKeepsTheAlwaysOnWorkGuidance() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("P")
        assertTrue(brief.contains("This computer is always on"))
        assertTrue(brief.contains("keeps running after your turn ends"))
        // v30: the closing summary is scoped to turns that did work.
        assertTrue(brief.contains("When a turn actually involved doing work, end by stating what you did and what you changed"))
    }

    // v30: the v29 facts were right but Hermes recited them — the
    // phone-files disclaimer in every reply, unprompted, plus a stock
    // "What would you like to do?" tail on every message. The facts are
    // now silent background and the conversation rules are explicit.

    @Test
    fun remoteBriefFramesTheFactsAsSilentBackground() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("Swift Bose")
        assertTrue(brief.contains("Background, for your own orientation only"))
        assertTrue(brief.contains("do not announce it, repeat it, or remind the user about it"))
    }

    @Test
    fun remoteBriefMentionsThePhoneLimitationOnlyWhenAsked() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("P")
        assertTrue(brief.contains("may be mentioned only when the user actually asks"))
        assertTrue(brief.contains("exactly one short line, once"))
        assertTrue(brief.contains("carry on with whatever you can do from here"))
    }

    @Test
    fun remoteBriefTreatsSmallTalkAsPlainChat() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("P")
        assertTrue(brief.contains("be a natural chat partner"))
        assertTrue(brief.contains("If the user just greets you or makes small talk, just chat back"))
        assertTrue(brief.contains("no project talk, no capability talk, no disclaimers"))
    }

    @Test
    fun remoteBriefMatchesTheUsersLanguageAndLength() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("P")
        assertTrue(brief.contains("Match the user's language and register"))
        assertTrue(brief.contains("Roman Hinglish"))
        assertTrue(brief.contains("not stiff, formal language"))
        assertTrue(brief.contains("a short message gets a short reply"))
        assertTrue(brief.contains("do not pad"))
    }

    @Test
    fun remoteBriefForbidsStockClosingQuestions() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("P")
        assertTrue(brief.contains("Do not end replies with a stock question"))
        assertTrue(brief.contains("\"What would you like to do?\""))
        assertTrue(brief.contains("\"Kya chahiye?\""))
        assertTrue(brief.contains("end naturally"))
        assertTrue(brief.contains("only when you genuinely need the answer to proceed"))
    }

    @Test
    fun remoteBriefForbidsRepeatsAndRepeatedApologies() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("P")
        assertTrue(brief.contains("Do not repeat anything you have already said in earlier turns"))
        assertTrue(brief.contains("do not keep apologising"))
    }

    @Test
    fun remoteBriefScopesTheClosingSummaryToWorkTurns() {
        val brief = AgentEnvironmentBrief.buildRemoteHermes("P")
        assertTrue(brief.contains("When a turn actually involved doing work"))
        assertTrue(brief.contains("in a plain chat reply there is nothing to report"))
    }
}
