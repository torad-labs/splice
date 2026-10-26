// Key rotation (Oct 26, 2026): `splice sessions relaunch`, run once the key is rotated. A session splice launched
// before the key helper holds its turn key in ANTHROPIC_AUTH_TOKEN, which a rotated key strands, and a session's hosted
// MCP servers hold the MCP key its launch handed it, which the rotation replaces, so each such session is relaunched on
// its own head, resumed by its own id. The sessions are this machine's registry, environments, command lines and launch
// records, written here; the daemon's relaunch, which ends a client in its terminal and starts it again, cannot run
// here and is answered by a stand-in that keeps what it was asked.
package splice.app.cli.sessions

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.mcp.HostedMcpOverlay
import splice.client.mcp.McpAccessKey
import splice.client.mcp.McpSharing
import splice.core.config.MgmtKey
import splice.core.config.RetiredKeys
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.process.LaunchOwners
import splice.core.process.LaunchProcess
import splice.core.terminal.TerminalOutput
import splice.core.util.LogSink
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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

// why: the person's own Anthropic login, which a head that forwards the caller's credential leaves in the session
private const val OWN_LOGIN = "sk-ant-oat01-the-persons-own-login"

private const val RELAUNCHED = """{"session_id":"s-101","head":"relay"}"""

// why: a stdio server whose arguments name no place, which splice hosts once for every session
private const val USER_SERVERS = """{"mcpServers": {"exa": {"command": "npx", "args": ["-y", "exa-mcp"]}}}"""

private const val HOUR = 3600L

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
        val planted = planted()
        val sessions = listOf(
            session("builder", 101, SessionRoute.Head("relay"), planted),
            session("launched-with-helper", 102, SessionRoute.Head("relay"), "HOME=/home/me"),
            session("direct", 103, SessionRoute.Direct, planted),
            session("exited", 104, SessionRoute.Head("relay"), planted, SessionAvailability.GONE),
            session("me", 105, SessionRoute.Head("relay"), planted),
            session("busy", 106, SessionRoute.Head("codex"), planted, SessionAvailability.STALE),
        )
        val stand = standIn(mapOf("s-101" to (200 to RELAUNCHED), "s-106" to (409 to WORKING)))

        val exit = relaunch(sessions, ownPids = setOf(105L, 1L))

        assertEquals(listOf("s-101", "s-106"), stand.asked, "relaunched: $said")
        assertEquals(1, exit, "a session left where it is fails the run, so it is run again: $said")
        assertTrue(said.any { "builder" in it && "relaunched" in it && "relay" in it }, "$said")
        assertTrue(said.any { "busy" in it && "it is working; relaunch it once its turn ends" in it }, "$said")
        assertTrue(said.any { "me" in it && "-r s-105" in it }, "its own session is named, with its resume: $said")
        assertTrue(said.none { turnKey() in it || daemonKey() in it }, "no key: $said")
    }

    // Only a session holding one of splice's keys was planted it: the turn key, the management key a session launched
    // before the turn key holds, or one a rotation retired. A head that forwards the person's own login runs a session
    // whose ANTHROPIC_AUTH_TOKEN is his own credential, and a LAN node's session holds the word its launch plants there.
    // A relaunch hands neither anything new and would end them on every run, so they are left alone and pass the run.
    @Test
    fun `only a session holding a key of splice's is relaunched, and a person's own token is left alone`() {
        val keys = MgmtKey(statePaths())
        val retiredTurnKey = TurnKey(keys).get()
        RetiredKeys(statePaths()).retire(keys.get())
        keys.replace()
        val sessions = listOf(
            session("retired", 101, SessionRoute.Head("relay"), "ANTHROPIC_AUTH_TOKEN=$retiredTurnKey"),
            session("before-the-split", 102, SessionRoute.Head("relay"), "ANTHROPIC_AUTH_TOKEN=${keys.get()}"),
            session("own-login", 103, SessionRoute.Head("claude"), "ANTHROPIC_AUTH_TOKEN=$OWN_LOGIN"),
            session("lan-node", 104, SessionRoute.Head("relay"), "ANTHROPIC_AUTH_TOKEN=splice-lan"),
        )
        val stand = standIn(mapOf("s-101" to (200 to RELAUNCHED), "s-102" to (200 to RELAUNCHED)))

        val exit = relaunch(sessions, ownPids = emptySet())

        assertEquals(listOf("s-101", "s-102"), stand.asked, "$said")
        assertEquals(0, exit, "a session holding none of splice's keys is not left, so the run passes: $said")
        assertTrue(said.none { OWN_LOGIN in it || retiredTurnKey in it || keys.get() in it }, "no key: $said")
    }

    // PR #247's fifth review: a rotation replaces the MCP access key too. A session launched with the key helper holds
    // no key in its environment, but its hosted MCP servers hold the MCP key its launch handed it until it ends. One
    // launched before the rotation is relaunched, though its head's file was since rewritten with the new key, and one
    // launched after it is left alone.
    @Test
    fun `after a rotation, a session whose hosted MCP servers hold the replaced key is relaunched, and no later one`() {
        val keys = MgmtKey(statePaths())
        val mcp = McpAccessKey(stateDir = statePaths().stateDir) { keys.get() }
        Files.writeString(tmp.resolve(".claude.json"), USER_SERVERS)
        val overlay = HostedMcpOverlay(tmp, sharing(mcp), statePaths().stateDir, LogSink {})
        val launched = listOf("--mcp-config", checkNotNull(overlay.file("claude-splice")).toString())
        val replaced = mcp()
        val before = Instant.now().minusSeconds(HOUR)
        RetiredKeys(statePaths()).retire(keys.get())
        keys.replace()
        mcp.managementReplaced()
        overlay.file("claude-splice")
        val after = Instant.now().plusSeconds(1)
        val sessions = listOf(
            session("before-the-rotation", 101, SessionRoute.Head("relay"), "HOME=/home/me"),
            session("after-the-rotation", 102, SessionRoute.Head("relay"), "HOME=/home/me"),
        )
        startedWith(101, launched)
        startedWith(102, launched)
        val stand = standIn(mapOf("s-101" to (200 to RELAUNCHED)))

        val exit = relaunch(sessions, ownPids = emptySet(), births = mapOf(101L to before, 102L to after))

        assertEquals(listOf("s-101"), stand.asked, "$said")
        assertEquals(0, exit, "$said")
        assertTrue(said.none { replaced in it || mcp() in it }, "no key: $said")
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
            session("builder", 101, SessionRoute.Head("relay"), planted()),
            session("reviewer", 107, SessionRoute.Head("relay"), planted()),
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

    private fun relaunch(
        sessions: List<SessionRecord>,
        ownPids: Set<Long>,
        unlisted: String? = null,
        births: Map<Long, Instant> = emptyMap(),
    ): Int {
        val state = tmp.resolve("state")
        // each launch splice made declares its process and when it started, as the launcher does before exec
        val owners = LaunchOwners(state) { pid -> births[pid]?.let(::LaunchProcess) }
        births.keys.forEach { owners.write(it, "relay", "http://127.0.0.1:1", "session", "other") }
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
            environment = ProcessEnvironment(tmp.resolve("proc"), owners, DarwinPs(here = false)),
            ancestors = SessionsRelaunchCommand.Ancestors { ownPids },
        ).run()
    }

    private fun statePaths(): StatePaths = StatePaths(baseOverride = tmp.resolve("state"))

    private fun sharing(bearer: McpAccessKey): McpSharing =
        McpSharing(enabled = true, exclude = emptySet(), endpointPrefix = "http://127.0.0.1:3096/mcp/", bearer = bearer)

    private fun daemonKey(): String = MgmtKey(statePaths()).get()

    /** The turn key the daemon hands a session it launches, made from its management key. */
    private fun turnKey(): String = TurnKey(MgmtKey(statePaths())).get()

    /** The entry a session launched before the key helper holds: its turn key, which the command must never print. */
    private fun planted(): String = "ANTHROPIC_AUTH_TOKEN=${turnKey()}"

    /** [pid]'s command line as /proc keeps it: the command, then each of [arguments], each ended by a NUL. */
    private fun startedWith(pid: Long, arguments: List<String>) {
        val line = (listOf("claude") + arguments).joinToString("") { "$it\u0000" }
        Files.write(tmp.resolve("proc").resolve(pid.toString()).resolve("cmdline"), line.toByteArray())
    }

    private fun standIn(answers: Map<String, Pair<Int, String>>): StandInDaemon =
        StandInDaemon(daemonKey(), answers).also { daemon = it }

    /** A registered session, `s-<pid>`, whose process environment holds the one entry [environ], started with no
     *  arguments. */
    private fun session(
        name: String,
        pid: Long,
        route: SessionRoute,
        environ: String,
        availability: SessionAvailability = SessionAvailability.LIVE,
    ): SessionRecord {
        val dir = Files.createDirectories(tmp.resolve("proc").resolve(pid.toString()))
        Files.write(dir.resolve("environ"), environ.toByteArray())
        startedWith(pid, emptyList())
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
