// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): `splice peer codex attach` and `detach`. Attach makes
// a native Codex thread the peer `codex-<thread id>` and starts its helper; a Codex hook runs it on each event, and it
// returns at once once the peer stands as asked. Inside a Codex conversation the thread is $CODEX_THREAD_ID and the home
// $CODEX_HOME, and the Codex process the record binds is the one above the command, found in its own ancestry.
package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
private const val CODEX_HOME_ENV = "CODEX_HOME"

/** `splice peer codex <attach|detach>`. */
internal class PeerCodexCommand(
    private val call: PeerCall,
    private val out: TerminalOutput,
    private val env: EnvReader,
) {
    private val names = CodexNames()

    fun run(args: List<String>): Int? = when (args.firstOrNull()) {
        "attach" -> attach(args.drop(1))
        "detach" -> detach(args.drop(1))
        else -> null
    }

    /** `attach [<thread>] [--cwd DIR] [--codex-home DIR] [--json]`. */
    private fun attach(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf("--cwd", "--codex-home"), setOf("--json")).parse(args)
        val thread = parsed?.let { threadOf(it.words) } ?: return null
        val key = call.managementKey() ?: return PeerExit.REFUSED
        return when (val answer = call.call("POST", "/api/peers/codex", key, body(thread, parsed.values))) {
            is PeerAnswer.Exit -> answer.code
            is PeerAnswer.Ok -> {
                if ("--json" in parsed.switches) out.line(answer.body.toString()) else attached(answer)
                PeerExit.DONE
            }
        }
    }

    /** The attach: the thread, its Codex home and working directory, and the Codex process above this command. */
    private fun body(thread: String, values: Map<String, String>): JsonObject {
        val home = values["--codex-home"] ?: env(CODEX_HOME_ENV) ?: UserHome.dir().resolve(".codex").toString()
        val owner = CodexOwners().find()
        return buildJsonObject {
            put("thread", thread)
            put("home", absolute(home))
            put("cwd", absolute(values["--cwd"].orEmpty()))
            if (owner != null) {
                putJsonObject("owner") {
                    put("pid", owner.pid)
                    put("procStart", owner.procStart)
                    put("pidDomain", owner.pidDomain)
                }
            }
        }
    }

    private fun attached(answer: PeerAnswer.Ok) {
        val reply = answer.body
        out.line("attached ${JsonScalars.str(reply, "name")}")
        out.line("  session id  ${JsonScalars.str(reply, "sessionId")}")
        out.line("  key file    ${JsonScalars.str(reply, "keyFile")}")
        out.line("  socket      ${JsonScalars.str(reply, "socket") ?: "not yet: the peer is still starting"}")
        out.line("  owner       ${JsonScalars.str(reply, "owner")?.let { "codex pid $it" } ?: "none found above"}")
    }

    /** `detach [<thread>] [--purge]`: revokes the thread's peer; its journal stays unless --purge. */
    private fun detach(args: List<String>): Int? {
        val parsed = PeerArgsParser(emptySet(), setOf("--purge")).parse(args)
        val thread = parsed?.let { threadOf(it.words) } ?: return null
        val key = call.managementKey() ?: return PeerExit.REFUSED
        val name = names.peer(thread)
        val path = "/api/peers/${URLEncoder.encode(name, Charsets.UTF_8)}?purge=${"--purge" in parsed.switches}"
        return when (val answer = call.call("DELETE", path, key)) {
            is PeerAnswer.Exit -> answer.code
            is PeerAnswer.Ok -> {
                out.line("detached $name")
                PeerExit.DONE
            }
        }
    }

    /** The thread named in [words], else the Codex conversation this runs in; null when there is neither. */
    private fun threadOf(words: List<String>): String? {
        val named = if (words.size > 1) null else words.firstOrNull() ?: env(CODEX_THREAD_ENV) ?: env(CODEX_SESSION_ENV)
        return named?.let(names::thread)
    }

    private fun absolute(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()
}
