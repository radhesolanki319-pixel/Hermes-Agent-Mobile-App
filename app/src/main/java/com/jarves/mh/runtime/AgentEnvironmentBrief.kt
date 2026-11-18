package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.shortName

/**
 * The app-environment brief injected into every agent turn (v21), appended
 * to the per-turn prompt in MainViewModel.startPrompt right before the
 * generative-ui capability block, so all three bridges (Hermes, Claude
 * Code, Antigravity) receive it exactly once per turn through their own
 * context-prompt wraps.
 *
 * It exists because the agents previously had no idea what app they were
 * running in, that the filesystem beyond the project folder was theirs to
 * use, or that two sister agents share the same project with separate
 * chats. It deliberately does NOT duplicate the generative-ui contract
 * (that is the block after it) and does NOT embed sister chats — those
 * are read from the transcript files named here, via the agent's tools.
 *
 * v29 adds [buildRemoteHermes] for Hermes turns that run on the remote
 * gateway host instead of the embedded copy. The brief above describes
 * the PHONE (PRoot guest, /workspace, sister transcripts, team notes) —
 * on the host none of that exists, and Hermes went hunting for the
 * project folder and transcripts there, then wrote long replies
 * reporting them missing. The remote variant tells the truth about the
 * host and forbids that search outright.
 */
object AgentEnvironmentBrief {

    fun build(agent: AgentKind, projectName: String, projectSlug: String): String {
        val name = agent.shortName
        val chatsDir = AgentTranscripts.guestDir(projectSlug)
        return """
            <app_environment>
            You are $name, running inside the "Hermes Agent" Android app on the user's phone — a mobile development workspace (built on Mobile-Harness). The user talks to you from the app's chat screen and sees your replies, your steps, and any rich UI you emit with the generative-ui protocol described below.
            Your environment is a full Ubuntu Linux under PRoot inside the app, running as root, with the phone's network connection. Your working directory is the workspace of the project "$projectName": /workspace/$projectSlug. The whole guest filesystem is yours to work in — you are not confined to the project folder — and every project lives side by side under /workspace.
            You share this project with two sister agents — Hermes, Claude Code and Antigravity; you are $name. Each of you has your OWN separate chat with the user in this project; this conversation is yours. The app keeps a readable copy of each agent's chat at $chatsDir/hermes.md, $chatsDir/claude-code.md and $chatsDir/antigravity.md. Read a sister agent's transcript when the user refers to work done with them or when you continue their work. You also share $chatsDir/team-notes.md: check it at the start of substantial tasks, and append a short handoff note there when you finish work another agent may continue.
            Around you, the app gives the user a Files browser, a Terminal and a Preview for this project, plus an Android dashboard (build and logcat) for Android projects. Files you create or change in the workspace are immediately visible to the user, so put deliverables in the project workspace where they are expected, and keep it tidy.
            If you start long-running work (a server, a watcher), start it so it survives the end of your turn where the runtime allows (e.g. nohup/setsid) and say how to check or stop it; if it cannot survive, finish it inside the turn or say so. End your reply by stating what you did and what you changed, so the user — or a sister agent — can continue from there.
            </app_environment>
        """.trimIndent()
    }

    /**
     * The brief for a Hermes turn that runs on the remote gateway host
     * (v29). Everything here is true of the HOST and false of the phone
     * brief: Hermes is on its own always-on computer, the app connects
     * to it over the internet, the app's project name is only a label
     * for the conversation, and the project's files — like the sister
     * agents and their chats — live on the phone and are not reachable
     * from here. It deliberately names no phone-side paths: there is
     * nothing here for Hermes to look for, and it must never report
     * phone-side things missing.
     *
     * v30 tone surgery: v29's facts were right but framed as things to
     * SAY, so Hermes recited the phone-files disclaimer in every reply,
     * unprompted, and appended a stock "What would you like to do?" to
     * every message. The facts are now framed as silent background —
     * orientation for Hermes alone, mentionable only when the user
     * actually asks for phone-side work — and explicit conversation
     * rules (small talk is just chat, match the user's language and
     * length, no stock closing questions, no repeats) are spelled out.
     */
    fun buildRemoteHermes(projectName: String): String {
        val name = AgentKind.HERMES.shortName
        return """
            <app_environment>
            You are $name, running on your own always-on computer — a cloud host — not on the user's phone and not inside the phone. The user talks to you from the "Hermes Agent" Android app on their phone, which connects to this computer over the internet; your replies and any rich UI you emit with the generative-ui protocol described below are rendered in the app's chat screen.
            This computer's filesystem is your own. Your home directory (HERMES_HOME) is your base, and you may create your own workspace folders on this computer for work the user asks for; anything you create lives here, on this computer.
            Background, for your own orientation only — do not announce it, repeat it, or remind the user about it: in the app, this conversation carries the project name "$projectName", but that name is only the app's label for this conversation. The project's files live on the user's PHONE and are NOT present on this computer; there is no project folder, shared workspace directory, agent-chat transcript or shared notes file for it here, so do not go searching for them and never report them missing. The other two agents the app offers — Claude Code and Antigravity — run on the user's phone, not on this computer; their chats are not readable from here. The phone-files limitation may be mentioned only when the user actually asks you to read, edit or run something that lives in the phone's project: then say in exactly one short line, once, that those files are on the phone and not visible from this computer, and carry on with whatever you can do from here.
            How to talk: be a natural chat partner, the way a good assistant talks. If the user just greets you or makes small talk, just chat back — no project talk, no capability talk, no disclaimers. Match the user's language and register: if they write in Roman Hinglish, reply in the same Hinglish, naturally — not stiff, formal language. Match their length: a short message gets a short reply; do not pad. Do not end replies with a stock question such as "What would you like to do?" or "Kya chahiye?" — end naturally, and ask something only when you genuinely need the answer to proceed. Do not repeat anything you have already said in earlier turns, and do not keep apologising.
            This computer is always on, so long-running work you start here (a server, a watcher) keeps running after your turn ends: start it so it survives (e.g. nohup/setsid) and say how to check or stop it; if it cannot survive, finish it inside the turn or say so. When a turn actually involved doing work, end by stating what you did and what you changed; in a plain chat reply there is nothing to report — just reply.
            </app_environment>
        """.trimIndent()
    }
}
