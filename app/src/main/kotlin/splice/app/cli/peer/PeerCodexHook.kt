// NEW: Oct 28, 2026 (docs/peers.md, "A Codex thread no app-server holds"): `splice peer codex hook`, the one command a
// Codex hook runs for every event. It reads the event from the payload on stdin (HarnessHook) and:
//   SessionStart       attaches the thread as its codex peer;
//   UserPromptSubmit   attaches it, then hands over the messages waiting for it as context;
//   PostToolUse        hands over the waiting messages as context;
//   Stop               hands them over as the reason to keep the turn going, so a message that came during the turn is
//                      read before the thread goes idle, and lets the turn end when none waits;
//   either of those    also attaches a thread whose peer no host serves yet: one mid-turn when its hooks were first
//                      written, or one that started while the daemon was down, has no SessionStart or prompt to come;
//   SessionEnd         detaches it.
// The waiting messages are the ones the peer's helper left in CodexHookMailbox because no app-server holds the thread.
// Codex 0.159 reads a PostToolUse or UserPromptSubmit hook's `hookSpecificOutput.additionalContext` into the model's
// context, and a Stop hook's `{"decision":"block","reason"}` as the turn's next input. A hand-over reads files under the
// state folder and needs no daemon. Every event exits 0: a hook never blocks or fails the thread's turn, and a refusal
// or an unreachable daemon is said on stderr.
package splice.app.cli.peer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.app.cli.hook.HarnessHook
import splice.app.cli.hook.HookLook
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.sessions.peer.codex.CodexHookMailbox
import splice.sessions.peer.codex.CodexNames
import splice.sessions.peer.helper.PeerStateFiles
import splice.topology.TopologyStatePaths
import java.nio.file.Files

private const val CODEX_SESSION_START = "SessionStart"
private const val USER_PROMPT = "UserPromptSubmit"
private const val POST_TOOL = "PostToolUse"
private const val STOP = "Stop"
private const val SESSION_END = "SessionEnd"

// why: one blank line between messages, so each envelope reads as its own block.
private const val BETWEEN = "\n\n"

/** The thread a hook's payload, or the environment, names; null when none does. */
internal fun interface HookThreadId {
    fun of(look: HookLook): String?
}

/** One of the codex verbs a hook runs for a thread: attach or detach. Its exit code. */
internal fun interface CodexVerb {
    fun run(id: String, look: HookLook): Int
}

/** `splice peer codex hook`. [named] names the payload's thread; [attach] and [detach] are the codex verbs. */
internal class PeerCodexHook(
    private val out: TerminalOutput,
    private val env: EnvReader,
    private val hooks: HarnessHook,
    private val named: HookThreadId,
    private val attach: CodexVerb,
    private val detach: CodexVerb,
) {
    private val names = CodexNames()

    fun run(args: List<String>): Int? {
        val look = hooks.look(forced = true)
        val id = named.of(look)
        return when {
            args.isNotEmpty() -> look.refuseUsage()
            id == null -> look.noThread()
            else -> {
                answer(id, look)
                PeerExit.DONE
            }
        }
    }

    /** The exit codes of the attach and the detach are dropped: their refusals are already on stderr, and a hook
     *  always exits 0. */
    private fun answer(id: String, look: HookLook) {
        when (look.event) {
            CODEX_SESSION_START -> {
                val _ = attach.run(id, look)
            }
            USER_PROMPT -> {
                val waiting = take(id)
                val _ = attach.run(id, look)
                context(USER_PROMPT, waiting)
            }
            POST_TOOL -> {
                served(id, look)
                context(POST_TOOL, take(id))
            }
            STOP -> {
                served(id, look)
                keepGoing(take(id))
            }
            SESSION_END -> {
                val _ = detach.run(id, look)
            }
            else -> Unit
        }
    }

    /** Attaches [id] when no host has written its peer's helper.json yet; a served peer asks the daemon nothing. */
    private fun served(id: String, look: HookLook) {
        if (Files.exists(files(id).helperJson)) return
        val _ = attach.run(id, look)
    }

    /** The messages waiting for [id]'s peer, joined; empty when none waits. */
    private fun take(id: String): String = CodexHookMailbox(files(id).dir).take().joinToString(BETWEEN)

    private fun files(id: String): PeerStateFiles =
        PeerStateFiles(TopologyStatePaths(env).current().stateDir, names.peer(id))

    private fun context(event: String, text: String) {
        if (text.isEmpty()) return
        val line = buildJsonObject {
            putJsonObject("hookSpecificOutput") {
                put("hookEventName", event)
                put("additionalContext", text)
            }
        }
        out.line(line.toString())
    }

    private fun keepGoing(text: String) {
        if (text.isEmpty()) return
        out.line(
            buildJsonObject {
                put("decision", "block")
                put("reason", text)
            }.toString(),
        )
    }
}
