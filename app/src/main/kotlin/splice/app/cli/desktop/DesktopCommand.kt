// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). `splice desktop`: open Claude Desktop's door to splice,
// or close it, and say what is so. The daemon holds the door, the key and the heads, so every verb asks the running
// daemon (starting it when none runs) with the management key on disk, and prints its answer: what changed, which
// model a session starts on and why, what to set in Desktop's own environment store, or what made it refuse.
//
//   splice desktop [--json]                 whether Desktop is on, the door, the default model, the lineup, the heads
//                                           left out and why, and the last turn through the door
//   splice desktop on [--model <selector>]  open the door, and say what to set once in Desktop's environment store
//   splice desktop off                      close the door, and say what to remove there
//   splice desktop rotate-key               replace the desktop key, and say the new one while Desktop is on
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading

internal const val DESKTOP_USAGE = "[--json] | on [--model <selector>] | off | rotate-key"

internal class DesktopCommand(
    private val env: EnvReader = EnvReader(System::getenv),
    private val out: TerminalOutput = TerminalOutput { println(it) },
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val daemon: DaemonUp = DaemonUp(LifecycleWiring::ensureDaemon),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether a daemon answers on the control port once this returns. */
    fun interface DaemonUp {
        fun ensure(port: Int): Boolean
    }

    fun run(args: List<String>): Int = when {
        args.isEmpty() || args == listOf("--json") -> asked(json = args.isNotEmpty())
        args.first() == "on" -> on(args.drop(1))
        args.singleOrNull() in PLAIN_ACTS -> acted(args.first(), NO_BODY)
        else -> refused("usage: splice desktop $DESKTOP_USAGE")
    }

    /** `on`, or `on --model <selector>`. */
    private fun on(rest: List<String>): Int = when {
        rest.isEmpty() -> acted("on", NO_BODY)
        rest.size == 2 && rest.first() == "--model" ->
            acted("on", buildJsonObject { put("model", rest.last()) }.toString())
        else -> refused("usage: splice desktop $DESKTOP_USAGE")
    }

    /** What the daemon says of the door, as data or in words. */
    private fun asked(json: Boolean): Int {
        val reply = read() ?: return 1
        val state = parsed(reply)
        when {
            json -> out.line(reply.body)
            state == null -> return refused(UNREADABLE)
            else -> DesktopSaid(out).state(state)
        }
        return 0
    }

    /** One act of the switch, said in words. */
    private fun acted(verb: String, body: String): Int {
        val reply = posted(verb, body) ?: return 1
        val state = parsed(reply) ?: return refused(UNREADABLE)
        JsonScalars.str(state, "said")?.let { out.line("splice: $it.") }
        if (verb == "on") JsonScalars.str(state, "default_from")?.let { out.line("splice: a session starts on $it.") }
        DesktopSaid(out).env(state)
        return 0
    }

    /** The daemon's 200 answer to `GET /api/desktop`, or null once what went wrong is said. */
    private fun read(): ControlReply? {
        val (port, key) = reached() ?: return null
        val reply = ControlPlaneClient.send(api(port, ""), "GET", key, STATE_MS)
            ?: return null.also { refused("the daemon didn't answer") }
        return reply.takeIf { it.status == HttpStatus.OK } ?: null.also { refused(why(reply)) }
    }

    /** The daemon's 200 answer to one act, or null once what went wrong is said. */
    private fun posted(verb: String, body: String): ControlReply? {
        val (port, key) = reached() ?: return null
        val reply = answered(ControlPlaneClient.post(api(port, "/$verb"), key, body, ACT_MS)) ?: return null
        return reply.takeIf { it.status == HttpStatus.OK } ?: null.also { refused(why(reply)) }
    }

    /** The reply [read] carries, or null once why there is none is said. */
    private fun answered(read: PostReading): ControlReply? = when (read) {
        is PostReading.Answered -> read.reply
        PostReading.NotConnected -> null.also { refused(NOT_CONNECTED) }
        PostReading.CutAfterSending -> null.also { refused(CUT) }
    }

    private fun api(port: Int, path: String): String = "http://127.0.0.1:$port/api/desktop$path"

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

    private fun parsed(reply: ControlReply): JsonObject? = JsonScalars.objectOrNull(json, reply.body)

    private fun why(reply: ControlReply): String = when (reply.status) {
        HttpStatus.NOT_FOUND -> NO_DESKTOP_ROUTES
        else -> JsonScalars.str(parsed(reply), "error") ?: "the daemon answered HTTP ${reply.status}"
    }

    private fun refused(why: String): Int {
        err.line("splice desktop: $why")
        return 1
    }
}

private const val NO_BODY = "{}"
private val PLAIN_ACTS = setOf("off", "rotate-key")
private const val UNREADABLE = "the daemon's answer could not be read"
private const val NOT_CONNECTED = "the daemon didn't answer; nothing was changed"
private const val CUT = "the connection dropped before the daemon answered; run `splice desktop` to see what is so"
private const val NO_DESKTOP_ROUTES =
    "the daemon that is running is older than this splice; run `splice restart`, then try again"

// why: reading the door's state reads each head's login; a busy daemon still answers well inside this
private const val STATE_MS = 15_000

// why: an act writes two small files; a daemon busy with turns still answers well inside this
private const val ACT_MS = 30_000
