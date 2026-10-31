// Key rotation (Oct 26, 2026): `splice rotate-key` replaces the management key in one command, while the daemon runs.
// The daemon holds the key in memory, so the file is never rewritten from here: the command asks the running daemon,
// with the key on disk, and the daemon replaces the key, the turn key made from it, the turn-key header file every
// launched session reads, the MCP access key bound to it and the console pages an older splice left. The command says
// what changed in words and paths. No key reaches its output, its argv or the request's body.
package splice.app.cli.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.app.control.HEADER_WRITTEN
import splice.app.control.MCP_REPLACED
import splice.app.control.RETIRED_KEPT
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading

// why: a rotation writes three small files and one publication; a daemon busy with turns still answers well inside this
private const val ROTATE_MS = 30_000

/** `splice rotate-key`: the one command that replaces the management key. [daemon] answers whether a daemon serves
 *  the control port, starting it when none does, as `splice console` does. */
internal class RotateKeyCommand(
    private val env: EnvReader = EnvReader(System::getenv),
    private val out: TerminalOutput = TerminalOutput { println(it) },
    private val daemon: DaemonUp = DaemonUp(LifecycleWiring::ensureDaemon),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether a daemon answers on the control port once this returns. */
    fun interface DaemonUp {
        fun ensure(port: Int): Boolean
    }

    fun run(): Int {
        val port = AdminSupport.controlPort(env)
        if (!daemon.ensure(port)) return refused("the daemon isn't running and couldn't be started")
        return when (val read = AdminSupport.readMgmtKey(env)) {
            is MgmtKeyRead.Present -> rotateWith(port, read.key)
            is MgmtKeyRead.Unreadable -> refused("the management key is unreadable (${read.reason})")
            is MgmtKeyRead.Absent -> refused("the daemon has no management key yet")
        }
    }

    /** The key goes only into the request's Authorization header. */
    private fun rotateWith(port: Int, key: String): Int {
        val url = "http://127.0.0.1:$port/api/mgmt-key/rotate"
        return when (val reading = ControlPlaneClient.post(url, key, "{}", ROTATE_MS)) {
            is PostReading.Answered -> answered(reading.reply)
            PostReading.NotConnected -> refused("the daemon didn't answer")
            PostReading.CutAfterSending -> cut(key)
        }
    }

    private fun answered(reply: ControlReply): Int = when (reply.status) {
        HttpStatus.OK -> replaced(JsonScalars.objectOrNull(json, reply.body))
        HttpStatus.NOT_FOUND -> refused(
            "the daemon that is running is older than this splice and can't replace its key. " +
                "Run `splice restart`, then `splice rotate-key` again",
        )
        HttpStatus.UNAUTHORIZED -> refused(
            "the daemon refused the management key on disk, as it does when the file changed after it started. " +
                "Run `splice restart`, then `splice rotate-key` again",
        )
        else -> refused("the daemon answered HTTP ${reply.status}")
    }

    /** The connection dropped after the request went up, so the daemon may have replaced the key: the file says. */
    private fun cut(sent: String): Int {
        val now = (AdminSupport.readMgmtKey(env) as? MgmtKeyRead.Present)?.key
        val changed = now != null && now != sent
        return if (changed) replaced(null) else refused("the connection dropped before the daemon answered")
    }

    /** What changed, from the daemon's answer when there is one. Paths and words only: the answer carries no key. */
    private fun replaced(answer: JsonObject?): Int {
        out.line("splice: the management key was replaced. The old key, and the turn key made from it, are refused.")
        out.line(
            "splice: sessions splice launched with a key helper read the new turn key at their next request and " +
                "carry on on their own head. A session launched before that holds the old turn key in its " +
                "environment: relaunch it with `splice sessions relaunch`.",
        )
        when (val mcp = JsonScalars.str(answer, "mcp_access")) {
            null -> Unit
            MCP_REPLACED -> out.line(
                "splice: the MCP access key was replaced too. A running session reaches its hosted MCP servers again " +
                    "once it is relaunched: `splice sessions relaunch` finds each one. Run it until it passes.",
            )
            else -> out.line("splice: the MCP access key was not replaced ($mcp); `splice restart` replaces it.")
        }
        JsonScalars.str(answer, "turn_header")?.takeIf { it != HEADER_WRITTEN }?.let {
            out.line("splice: the turn-key file was not rewritten ($it); `splice restart` rewrites it.")
        }
        JsonScalars.str(answer, "retired_keys")?.takeIf { it != RETIRED_KEPT }?.let {
            out.line(
                "splice: the old keys could not be kept as retired ($it), so a session that still holds the old turn " +
                    "key may send it on to a head that forwards your own login, and `splice sessions relaunch` " +
                    "won't find that session.",
            )
        }
        out.line("splice: the console signs in again with `splice console`.")
        JsonScalars.str(answer, "key_file")?.let {
            out.line("splice: a tool outside splice that read the key from $it must read it again.")
        }
        return 0
    }

    private fun refused(why: String): Int {
        out.line("splice: $why. The management key was not replaced.")
        return 1
    }
}
