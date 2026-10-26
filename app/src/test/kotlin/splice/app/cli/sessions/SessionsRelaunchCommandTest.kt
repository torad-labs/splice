// Key rotation (Oct 26, 2026): `splice sessions relaunch`, rotation day's first step. A session splice launched before
// the key helper holds its turn key in ANTHROPIC_AUTH_TOKEN, which a rotated key strands, so each such session is
// relaunched on its own head, resumed by its own id, before the key is replaced. The sessions are this machine's
// registry and environments, written here; the daemon's relaunch, which ends a client in its terminal and starts it
// again, cannot run here and is answered by a stand-in that keeps what it was asked.
package splice.app.cli.sessions

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.process.LaunchOwners
import splice.core.terminal.TerminalOutput
import splice.sessions.registry.DarwinPs
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

// why: what a session launched before the helper holds; the command must never print it
private const val PLANTED = "ANTHROPIC_AUTH_TOKEN=turn-key-from-before-the-helper"

private const val RELAUNCHED = """{"session_id":"s-101","head":"relay"}"""
private const val WORKING = """{"error":"it is working; relaunch it once its turn ends","reason":"working"}"""

class SessionsRelaunchCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val said = CopyOnWriteArrayList<String>()
    private var daemon: StandInDaemon? = null

    @AfterEach
    fun stop() {
        daemon?.stop()
    }

    @Test
    fun `each running session on a head that holds a planted key is relaunched, and no other`() {
        val sessions = listOf(
            session("builder", 101, SessionRoute.Head("relay"), PLANTED),
            session("launched-with-helper", 102, SessionRoute.Head("relay"), "HOME=/home/me"),
            session("direct", 103, SessionRoute.Direct, PLANTED),
            session("exited", 104, SessionRoute.Head("relay"), PLANTED, SessionAvailability.GONE),
            session("me", 105, SessionRoute.Head("relay"), PLANTED),
            session("busy", 106, SessionRoute.Head("codex"), PLANTED, SessionAvailability.STALE),
        )
        val stand = standIn(mapOf("s-101" to (200 to RELAUNCHED), "s-106" to (409 to WORKING)))

        val exit = relaunch(sessions, ownPids = setOf(105L, 1L))

        assertEquals(listOf("s-101", "s-106"), stand.asked, "relaunched: $said")
        assertEquals(1, exit, "a session left where it is fails the run, so it is run again: $said")
        assertTrue(said.any { "builder" in it && "relaunched" in it && "relay" in it }, "$said")
        assertTrue(said.any { "busy" in it && "it is working; relaunch it once its turn ends" in it }, "$said")
        assertTrue(said.any { "me" in it && "-r s-105" in it }, "its own session is named, with its resume: $said")
        assertTrue(said.none { "turn-key-from-before-the-helper" in it || daemonKey() in it }, "no key: $said")
    }

    @Test
    fun `with no session holding a planted key nothing is relaunched, and it says so`() {
        val sessions = listOf(session("launched-with-helper", 102, SessionRoute.Head("relay"), "HOME=/h"))
        val stand = standIn(emptyMap())

        val exit = relaunch(sessions, ownPids = emptySet())

        assertEquals(0, exit, "$said")
        assertTrue(stand.asked.isEmpty())
        assertTrue(said.isNotEmpty(), "it says there is nothing to relaunch")
    }

    @Test
    fun `a daemon older than the relaunch is named once, and nothing more is asked of it`() {
        val sessions = listOf(
            session("builder", 101, SessionRoute.Head("relay"), PLANTED),
            session("reviewer", 107, SessionRoute.Head("relay"), PLANTED),
        )
        val stand = standIn(emptyMap())

        val exit = relaunch(sessions, ownPids = emptySet())

        assertEquals(1, exit)
        assertEquals(listOf("s-101"), stand.asked, "every later ask would get the same answer")
        assertTrue(said.any { "restart" in it }, "it says the daemon must be restarted on this splice: $said")
    }

    @Test
    fun `a registry that could not be listed fails the run, since no session was seen to be clear`() {
        val stand = standIn(emptyMap())

        val exit = relaunch(emptyList(), ownPids = emptySet(), unlisted = "permission denied")

        assertEquals(1, exit, "$said")
        assertTrue(stand.asked.isEmpty())
        assertTrue(said.any { "permission denied" in it }, "$said")
    }

    private fun relaunch(sessions: List<SessionRecord>, ownPids: Set<Long>, unlisted: String? = null): Int {
        val state = tmp.resolve("state")
        val env = mapOf(
            "SPLICE_STATE_DIR" to state.toString(),
            "SPLICE_CONTROL_PORT" to checkNotNull(daemon).port.toString(),
            "SPLICE_CONFIG" to tmp.resolve("splice.toml").toString(),
            "HOME" to tmp.toString(),
        )
        return SessionsRelaunchCommand(
            env = { name -> env[name] },
            out = TerminalOutput { said.add(it) },
            sessions = Listed(sessions, unlisted),
            environment = ProcessEnvironment(tmp.resolve("proc"), LaunchOwners(state), DarwinPs(here = false)),
            ancestors = SessionsRelaunchCommand.Ancestors { ownPids },
        ).run()
    }

    private fun daemonKey(): String = MgmtKey(StatePaths(baseOverride = tmp.resolve("state"))).get()

    private fun standIn(answers: Map<String, Pair<Int, String>>): StandInDaemon =
        StandInDaemon(daemonKey(), answers).also { daemon = it }

    /** A registered session, `s-<pid>`, whose process environment holds the one entry [environ]. */
    private fun session(
        name: String,
        pid: Long,
        route: SessionRoute,
        environ: String,
        availability: SessionAvailability = SessionAvailability.LIVE,
    ): SessionRecord {
        val dir = Files.createDirectories(tmp.resolve("proc").resolve(pid.toString()))
        Files.write(dir.resolve("environ"), environ.toByteArray())
        return SessionRecord(
            sessionId = "s-$pid",
            name = name,
            status = SessionStatus(state = "idle"),
            route = route,
            availability = availability,
            process = SessionProcess(pid, tmp.toString(), null, null, null),
            client = SessionClient("cli", "2.1.310"),
        )
    }
}

private class Listed(private val records: List<SessionRecord>, private val error: String?) : SessionSource {
    override fun read(): List<SessionRecord> = records

    override fun list(): SessionListing = SessionListing(records, error)
}

/** The daemon's relaunch route as this test needs it: the management key opens it, each session id gets its answer
 *  (a daemon without the route answers 404 with no body), and every id it was asked is kept, in order. */
private class StandInDaemon(private val key: String, private val answers: Map<String, Pair<Int, String>>) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val asked = CopyOnWriteArrayList<String>()
    val port: Int get() = server.address.port

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val opened = exchange.requestHeaders.getFirst("Authorization") == "Bearer $key"
            val id = path.removePrefix("/api/sessions/").removeSuffix("/relaunch")
            val relaunchAsked = exchange.requestMethod == "POST" && path.endsWith("/relaunch")
            if (opened && relaunchAsked) asked.add(id)
            val (status, body) = if (opened) answers[id] ?: (404 to "") else 401 to """{"error":"unauthorized"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    fun stop() = server.stop(0)
}
