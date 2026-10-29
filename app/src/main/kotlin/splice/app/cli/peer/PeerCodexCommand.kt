// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): `splice peer codex attach` and `detach`, and since
// Oct 28 `hook`, the one command a Codex hook runs for every event (PeerCodexHook.kt). Attach makes
// a native Codex thread the peer `codex-<thread id>` and starts its helper; a Codex hook runs it on SessionStart,
// UserPromptSubmit and SessionEnd, and it returns at once once the peer stands as asked. The thread is the argument,
// else $CODEX_THREAD_ID or $CODEX_SESSION_ID when a caller set them. A hook sets neither: Codex writes `session_id`
// and `cwd` on stdin (HarnessHook). The home is $CODEX_HOME, and the Codex process the record binds is the one above
// the command, found in its own ancestry.
package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.app.cli.hook.HarnessHook
import splice.app.cli.hook.HookLook
import splice.app.cli.hook.SilentTerminal
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.sessions.peer.codex.CodexNames
import splice.sessions.peer.codex.CodexOwners
import java.net.URLEncoder
import java.nio.file.Path

internal const val CODEX_THREAD_ENV = "CODEX_THREAD_ID"
private const val CODEX_SESSION_ENV = "CODEX_SESSION_ID"

/** The variable that names a Codex home, which Codex itself reads. */
internal const val CODEX_HOME_ENV = "CODEX_HOME"
private val NONE: List<String> = emptyList()

/** `splice peer codex <attach|detach>`. */
internal class PeerCodexCommand(
    private val call: PeerCall,
    private val out: TerminalOutput,
    private val env: EnvReader,
    private val hooks: HarnessHook,
) {
    private val names = CodexNames()
    private val attachArgs = PeerArgsParser(setOf("--cwd", "--codex-home"), setOf("--json", HOOK_FLAG))
    private val detachArgs = PeerArgsParser(emptySet(), setOf("--purge", HOOK_FLAG))

    fun run(args: List<String>): Int? = when (args.firstOrNull()) {
        "attach" -> attach(args.drop(1))
        "detach" -> detach(args.drop(1))
        "hook" -> PeerCodexHook(out, env, hooks, { named(NONE, it) }, ::attached, ::detached).run(args.drop(1))
        else -> null
    }

    /** The attach a hook runs for [thread], with no flags; its exit code. */
    private fun attached(thread: String, look: HookLook): Int = post(thread, checkNotNull(attachArgs.parse(NONE)), look)

    /** The detach a hook runs for [thread], keeping the journal; its exit code. */
    private fun detached(thread: String, look: HookLook): Int =
        delete(thread, checkNotNull(detachArgs.parse(NONE)), look)

    /** `attach [<thread>] [--cwd DIR] [--codex-home DIR] [--json] [--hook]`. */
    private fun attach(args: List<String>): Int? {
        val parsed = attachArgs.parse(args)
        val look = hooks.look(parsed?.forcesHook() == true || HOOK_FLAG in args)
        val thread = parsed?.takeIf { it.words.size <= 1 }?.let { named(it.words, look) }
        return when {
            parsed == null || parsed.words.size > 1 -> look.refuseUsage()
            thread == null -> look.noThread()
            else -> post(thread, parsed, look)
        }
    }

    /** The attach: the thread, its Codex home and working directory, and the Codex process above this command. */
    private fun body(thread: String, values: Map<String, String>, look: HookLook): JsonObject {
        val home = values["--codex-home"] ?: env(CODEX_HOME_ENV) ?: UserHome.dir().resolve(".codex").toString()
        val cwd = values["--cwd"] ?: look.directory.orEmpty()
        val owner = CodexOwners().find()
        return buildJsonObject {
            put("thread", thread)
            put("home", absolute(home))
            put("cwd", absolute(cwd))
            if (owner != null) {
                putJsonObject("owner") {
                    put("pid", owner.pid)
                    put("procStart", owner.procStart)
                    put("pidDomain", owner.pidDomain)
                }
            }
        }
    }

    private fun post(thread: String, parsed: PeerArgs, look: HookLook): Int {
        val key = call.managementKey() ?: return look.keep(PeerExit.REFUSED)
        val answer = call.call("POST", "/api/peers/codex", key, body(thread, parsed.values, look))
        return when (answer) {
            is PeerAnswer.Exit -> look.keep(answer.code)
            is PeerAnswer.Ok -> {
                reported(look, parsed, answer)
                look.keep(PeerExit.DONE)
            }
        }
    }

    /** Operator output. A hook writes none of it: the lines are context, and the JSON is a decision. */
    private fun reported(look: HookLook, parsed: PeerArgs, answer: PeerAnswer.Ok) {
        val sink = if (look.active) SilentTerminal else out
        if ("--json" in parsed.switches) {
            sink.line(answer.body.toString())
        } else {
            val reply = answer.body
            sink.line("attached ${JsonScalars.str(reply, "name")}")
            sink.line("  session id  ${JsonScalars.str(reply, "sessionId")}")
            sink.line("  key file    ${JsonScalars.str(reply, "keyFile")}")
            sink.line("  socket      ${JsonScalars.str(reply, "socket") ?: "not yet: the peer is still starting"}")
            sink.line("  owner       ${JsonScalars.str(reply, "owner")?.let { "codex pid $it" } ?: "none found above"}")
        }
    }

    /** `detach [<thread>] [--purge] [--hook]`: revokes the thread's peer; its journal stays unless --purge. */
    private fun detach(args: List<String>): Int? {
        val parsed = detachArgs.parse(args)
        val look = hooks.look(parsed?.forcesHook() == true || HOOK_FLAG in args)
        val thread = parsed?.takeIf { it.words.size <= 1 }?.let { named(it.words, look) }
        return when {
            parsed == null || parsed.words.size > 1 -> look.refuseUsage()
            thread == null -> look.noThread()
            else -> delete(thread, parsed, look)
        }
    }

    private fun delete(thread: String, parsed: PeerArgs, look: HookLook): Int {
        val key = call.managementKey() ?: return look.keep(PeerExit.REFUSED)
        val name = names.peer(thread)
        val purge = "--purge" in parsed.switches
        val path = "/api/peers/${URLEncoder.encode(name, Charsets.UTF_8)}?purge=$purge"
        return when (val answer = call.call("DELETE", path, key)) {
            is PeerAnswer.Exit -> look.keep(answer.code)
            is PeerAnswer.Ok -> {
                if (!look.active) out.line("detached $name")
                look.keep(PeerExit.DONE)
            }
        }
    }

    /** The thread named in [words], else an env var a caller set, else the hook payload's session id. */
    private fun named(words: List<String>, look: HookLook): String? {
        val raw = words.firstOrNull() ?: env(CODEX_THREAD_ENV) ?: env(CODEX_SESSION_ENV) ?: look.sessionId
        return raw?.let(names::thread)
    }

    private fun absolute(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()
}
