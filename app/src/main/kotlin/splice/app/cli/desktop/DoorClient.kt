// NEW: door-default (docs/specs/door-default.md, decision 1). How `splice desktop` and `splice claude` reach the door
// the daemon holds: the running daemon (started when none runs), asked with the management key on disk at
// `/api/<area>`, and what went wrong said under the command's own name, `splice <area>: `.
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.app.cli.AdminSupport
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading

/** Whether a daemon answers on the control port once this returns. */
internal fun interface DaemonUp {
    fun ensure(port: Int): Boolean
}

internal class DoorClient(
    /** The command's name after `splice`, and the path its routes answer under `/api/`. */
    private val area: String,
    private val env: EnvReader,
    private val err: TerminalOutput,
    private val daemon: DaemonUp,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val opening = DoorOpening()

    /** The daemon's 200 answer to `GET /api/<area>[/<path>]`, or null once what went wrong is said. */
    fun read(path: String = ""): ControlReply? {
        val (port, key) = reached() ?: return null
        val at = if (path.isEmpty()) "" else "/$path"
        val reply = opening.reply { ControlPlaneClient.send(api(port, at), "GET", key, STATE_MS) }
            ?: return null.also { refused("the daemon didn't answer") }
        return reply.takeIf { it.status == HttpStatus.OK } ?: null.also { refused(why(reply)) }
    }

    /** The daemon's 200 answer to one act, `POST /api/<area>/<verb>` with [body], or null once what went wrong is
     *  said. */
    fun posted(verb: String, body: String): ControlReply? {
        val (port, key) = reached() ?: return null
        val reply = opening.reply { answered(ControlPlaneClient.post(api(port, "/$verb"), key, body, ACT_MS)) }
            ?: return null
        return reply.takeIf { it.status == HttpStatus.OK } ?: null.also { refused(why(reply)) }
    }

    fun parsed(reply: ControlReply): JsonObject? = JsonScalars.objectOrNull(json, reply.body)

    /** [why] said under the command's name; the exit code that goes with it. */
    fun refused(why: String): Int {
        err.line("splice $area: $why")
        return 1
    }

    /** The reply [read] carries, or null once why there is none is said. */
    private fun answered(read: PostReading): ControlReply? = when (read) {
        is PostReading.Answered -> read.reply
        PostReading.NotConnected -> null.also { refused(NOT_CONNECTED) }
        PostReading.CutAfterSending -> null.also { refused("$CUT `splice $area` to see what is so") }
    }

    private fun api(port: Int, path: String): String = "http://127.0.0.1:$port/api/$area$path"

    /** The control port and the management key, once a daemon answers there. */
    private fun reached(): Pair<Int, String>? {
        val port = AdminSupport.controlPort(env)
        if (!daemon.ensure(port)) return null.also { refused("the daemon isn't running and couldn't be started") }
        return when (val read = AdminSupport.readMgmtKey(env)) {
            is MgmtKeyRead.Present -> port to read.key
            is MgmtKeyRead.Unreadable -> null.also { refused("the management key is unreadable (${read.reason})") }
            is MgmtKeyRead.Absent -> null.also { refused("the daemon has no management key yet") }
        }
    }

    private fun why(reply: ControlReply): String = when (reply.status) {
        HttpStatus.NOT_FOUND -> DAEMON_TOO_OLD
        else -> JsonScalars.str(parsed(reply), "error") ?: "the daemon answered HTTP ${reply.status}"
    }
}

private const val NOT_CONNECTED = "the daemon didn't answer; nothing was changed"
private const val CUT = "the connection dropped before the daemon answered; run"
private const val DAEMON_TOO_OLD =
    "the daemon that is running is older than this splice; run `splice restart`, then try again"

// why: reading the door's state reads each head's login; a busy daemon still answers well inside this
private const val STATE_MS = 15_000

// why: an act writes two small files; a daemon busy with turns still answers well inside this
private const val ACT_MS = 30_000
