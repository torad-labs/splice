// Key rotation (Oct 26, 2026): `splice sessions relaunch`, run after `splice rotate-key` until it passes. A session
// splice launched before the key helper holds its turn key in ANTHROPIC_AUTH_TOKEN, which a rotated management key
// strands, and a session's hosted MCP servers hold the MCP key its launch handed it, which the rotation replaces (PR
// #247's fifth review). Each such session still running on a splice head is relaunched there by the daemon, one at a
// time: ended in its terminal and resumed by its own id on the command and the model it runs on, whose launch hands it
// the helper and the MCP key in force (SessionContinue.relaunch). The daemon leaves a working session, one waiting on
// an answer, one holding words the person has not sent and one holding a feedback draft not yet sent where they are,
// and this command does not relaunch the session it runs in. Each session left is named with why, and the run fails
// while any is left, so it is run again until it passes. Which sessions hold such a key is SessionKeys': a planted
// token counts only when it is one of splice's keys, so a head that forwards the person's own login and a LAN node's
// planted word are neither relaunched nor left (PR #247 review round 4). No key is printed: the management key goes
// only into the request's Authorization header, and a session's token is only compared.
package splice.app.cli.sessions

import kotlinx.serialization.json.Json
import splice.app.cli.AdminSupport
import splice.client.mcp.McpKeyHolders
import splice.core.config.RetiredKeys
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import splice.sessions.list.LocalSessions
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.topology.TopologyStatePaths
import java.net.URLEncoder

private const val VERB = "splice sessions relaunch"

// why: the daemon answers once the client has exited and come back on its command, or has gone back where it was when
// it did not, two arrival waits of 45 s at most; past this it is not answering
private const val RELAUNCH_MS = 180_000

private const val OLDER_DAEMON = "the daemon that is running is older than this splice and can't relaunch a session. " +
    "Run `splice restart` so it runs this splice, then `splice sessions relaunch` again"
private const val MGMT_KEY_REFUSED = "the daemon refused the management key on disk, as it does when the file " +
    "changed after it started. Run `splice restart`, then `splice sessions relaunch` again"

/** `splice sessions relaunch`. [ancestors] names the processes this command runs under, a session among them. */
internal class SessionsRelaunchCommand(
    private val env: EnvReader = EnvReader(System::getenv),
    private val out: TerminalOutput = TerminalOutput { println(it) },
    private val local: LocalSessions = LocalSessions(TerminalOutput(System.err::println), VERB),
    private val sessions: SessionSource = local.joined(env),
    private val environment: ProcessEnvironment = ProcessEnvironment(),
    private val ancestors: Ancestors = Ancestors {
        generateSequence(ProcessHandle.current()) { it.parent().orElse(null) }.map { it.pid() }.toSet()
    },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The pids of this process and every process above it. */
    fun interface Ancestors {
        fun pids(): Set<Long>
    }

    /** How one ask ended: relaunched, left where it is, or a daemon that is asked nothing more. */
    private enum class Asked { RELAUNCHED, LEFT, STOP }

    fun run(): Int {
        val listing = sessions.list()
        val unlisted = listing.error
        if (unlisted != null) {
            // a registry that could not be listed is no evidence that no session holds a key
            stop("the session registry could not be listed (${local.shown(unlisted)})")
            return 1
        }
        val onHeads = listing.sessions.filter(::runsOnAHead)
        return if (onHeads.isEmpty()) nothingToDo() else relaunchHolders(onHeads)
    }

    /** Each session in [onHeads] that holds a key the rotation strands is relaunched, but the one this command runs in.
     *  A token is splice's only when it is one of its keys, so the management key is read before any token is. */
    private fun relaunchHolders(onHeads: List<SessionRecord>): Int {
        val key = managementKey() ?: return 1
        val state = TopologyStatePaths(env).current()
        val keys = SessionKeys(environment, key, RetiredKeys(state), McpKeyHolders(state.stateDir), UserHome.dir(env))
        val read = onHeads.associateWith { keys.of(pidOf(it)) }
        // what was not read: the process is gone, or not this user's, or macOS hides its environment
        read.forEach { (record, held) ->
            held.unread?.let {
                out.line(
                    "  could not read $it of ${shown(record)}, so whether it holds one of splice's keys is not known",
                )
            }
        }
        val planted = read.filterValues { it == SessionKeys.Held.YES }.keys.toList()
        if (planted.isEmpty()) return nothingToDo()
        val own = ancestors.pids()
        val (mine, others) = planted.partition { pidOf(it) in own }
        mine.forEach(::ownSession)
        val relaunched = relaunchAll(key, others)
        return summary(relaunched, left = planted.size - relaunched)
    }

    private fun nothingToDo(): Int {
        out.line(
            "$VERB: no running session on a splice head holds one of splice's keys; nothing to do.",
        )
        return 0
    }

    /** The run's last line. It fails while any session is left, so it is run again until it passes. */
    private fun summary(relaunched: Int, left: Int): Int {
        val rest = if (left == 0) {
            "no running session holds one of splice's keys any more"
        } else {
            "left $left where they are. Run it again once they can be relaunched"
        }
        out.line("$VERB: relaunched $relaunched; $rest.")
        return if (left == 0) 0 else 1
    }

    private fun runsOnAHead(record: SessionRecord): Boolean = record.availability != SessionAvailability.GONE &&
        record.head != null && record.sessionId != null && record.process.pid != null

    private fun pidOf(record: SessionRecord): Long = checkNotNull(record.process.pid)

    /** This command runs in it: its relaunch would end the client waiting on this command's answer. */
    private fun ownSession(record: SessionRecord) {
        val id = local.shown(checkNotNull(record.sessionId))
        out.line(
            "  left ${shown(record)} where it is: this command runs in it. Relaunch it from another session, or " +
                "exit it and resume it on ${local.shown(checkNotNull(record.head))} with -r $id",
        )
    }

    /** Each relaunch asked in turn, and the count the daemon relaunched. */
    private fun relaunchAll(key: String, records: List<SessionRecord>): Int {
        val port = AdminSupport.controlPort(env)
        var relaunched = 0
        for (record in records) {
            when (ask(port, key, record)) {
                Asked.RELAUNCHED -> relaunched++
                Asked.LEFT -> Unit
                Asked.STOP -> break
            }
        }
        return relaunched
    }

    private fun managementKey(): String? = when (val read = AdminSupport.readMgmtKey(env)) {
        is MgmtKeyRead.Present -> read.key
        is MgmtKeyRead.Unreadable -> null.also { stop("the management key is unreadable (${read.reason})") }
        is MgmtKeyRead.Absent -> null.also { stop("the daemon has no management key yet") }
    }

    /** The key goes only into the request's Authorization header. */
    private fun ask(port: Int, key: String, record: SessionRecord): Asked {
        val id = URLEncoder.encode(checkNotNull(record.sessionId), Charsets.UTF_8).replace("+", "%20")
        val url = "http://127.0.0.1:$port/api/sessions/$id/relaunch"
        return when (val reading = ControlPlaneClient.post(url, key, "{}", RELAUNCH_MS)) {
            is PostReading.Answered -> answered(record, reading.reply)
            PostReading.NotConnected -> stop("the daemon didn't answer on port $port")
            PostReading.CutAfterSending -> left(record, "the connection dropped before the daemon answered")
        }
    }

    private fun answered(record: SessionRecord, reply: ControlReply): Asked {
        val error = JsonScalars.str(JsonScalars.objectOrNull(json, reply.body), "error")
        return when {
            reply.status == HttpStatus.OK -> Asked.RELAUNCHED.also { out.line("  relaunched ${shown(record)}") }
            reply.status == HttpStatus.UNAUTHORIZED -> stop(MGMT_KEY_REFUSED)
            // a daemon without the route answers 404 with no error of its own
            reply.status == HttpStatus.NOT_FOUND && error == null -> stop(OLDER_DAEMON)
            else -> left(record, error ?: "the daemon answered HTTP ${reply.status}")
        }
    }

    /** The daemon left it where it is, and why. It is relaunched by a later run, or by hand where splice cannot reach
     *  its terminal. */
    private fun left(record: SessionRecord, why: String): Asked {
        val id = local.shown(checkNotNull(record.sessionId))
        val head = local.shown(checkNotNull(record.head))
        out.line(
            "  left ${shown(record)} where it is: ${local.shown(why)}. Run this again once it can be relaunched, " +
                "or exit it and resume it on $head with -r $id",
        )
        return Asked.LEFT
    }

    private fun stop(why: String): Asked {
        out.line("$VERB: $why. Nothing more was relaunched.")
        return Asked.STOP
    }

    /** Its name and id, and the head it runs on, as `splice sessions` prints registry text. */
    private fun shown(record: SessionRecord): String {
        val id = local.shown(record.sessionId.orEmpty())
        val name = record.name?.let(local::shown)?.takeIf { it.isNotBlank() }
        val head = local.shown(record.head.orEmpty())
        return (if (name == null) id else "$name ($id)") + " on $head"
    }
}
