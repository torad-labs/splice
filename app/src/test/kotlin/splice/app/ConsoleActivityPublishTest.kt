// The daemon side of the console's activity stores. ConsoleEventPublisher is the one writer: a
// head's SendMessage edge becomes an edge row, its local label and its near-miss label query become
// activity rows under that head, and a fact with no session is stored nowhere. Also
// LaunchSpecFactory.headProjectsTrees, the head trees SessionProject's headless fallback searches
// before its vanilla default: every served head's projects tree, each a whole path.
//
// A SendMessage name is stored with the one live session that held it then, so a board read after the
// name moves files the call where it went.
package splice.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.SignInPlanner
import splice.app.control.SilentHeadProbes
import splice.app.head.LaunchSpecFactory
import splice.app.provider.HeadBuildInputs
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.HeadConfig
import splice.core.topology.Topology
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.ActivityRow
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.activity.RecipientResolution
import splice.sessions.http.ActivitySource
import splice.sessions.http.SentTextSource
import splice.sessions.http.TeamSource
import splice.sessions.http.TeamsRoutes
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import splice.sessions.transcript.SentTexts
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val NOW = 1_789_725_600_000L

/** A pid above Linux's pid_max ceiling (2^22): no process holds it, so its registration is GONE. */
private const val NO_PID = 99_999_999L

class ConsoleActivityPublishTest {

    @TempDir
    lateinit var tmp: Path

    /** Writes ride the single-threaded file lane, so draining it is an exact barrier: every row
     *  queued before this call is on disk after it. */
    private fun <T> await(read: () -> List<T>, count: Int): List<T> {
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        return read().also { assertEquals(count, it.size, it.toString()) }
    }

    @Test
    fun `a head's facts land in the stores under that head, and a sessionless fact nowhere`() {
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { NOW })
        val publisher = ConsoleEventPublisher(stores, WallClock { NOW })
        val codex = publisher.forHead("codex")
        codex.messageSent("s-1", "uds:/run/peer.sock", "toolu_1")
        codex.activityLabel(null, "Reading nothing")
        codex.labelQueryUpstream(null)
        codex.activityLabel("s-1", "Messaging a peer session")
        codex.labelQueryUpstream("s-1")
        assertEquals(
            listOf(MessageEdge("s-1", "uds:/run/peer.sock", NOW, "toolu_1")),
            await({ stores.edges.edges() }, 1),
        )
        assertEquals(
            listOf(
                ActivityRow(NOW, "s-1", "codex", "Messaging a peer session", upstream = false),
                ActivityRow(NOW, "s-1", "codex", null, upstream = true),
            ),
            await({ stores.activity.rows("s-1") }, 2),
        )
        assertEquals(
            2,
            Files.readAllLines(tmp.resolve("activity/activity-2026-09-18.jsonl")).size,
            "the two sessionless facts wrote no row at all",
        )
    }

    /** One session's availability as GET /api/sessions serves it. */
    private fun availability(port: Int, key: String, session: String): String {
        val ask = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/sessions"))
            .header("Authorization", "Bearer $key")
            .build()
        val body = HttpClient.newHttpClient().send(ask, HttpResponse.BodyHandlers.ofString()).body()
        return Json.parseToJsonElement(body).jsonObject.getValue("sessions").jsonArray
            .single { it.jsonObject["session_id"]?.jsonPrimitive?.content == session }
            .jsonObject.getValue("availability").jsonPrimitive.content
    }

    @Test
    fun `a SendMessage name is stored with the one live session holding it, and keeps it after the name moves`() {
        val paths = StatePaths(baseOverride = tmp.resolve("home/.splice/state"))
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { NOW })
        val names = ConsoleWiring.nameHolders(paths)
        val codex = ConsoleEventPublisher(stores, WallClock { NOW }, names = names).forHead("codex")
        val live = ProcessHandle.current().pid()
        ClaudeSessionFiles(paths).register("s-gpt-a", "gpt", live)
        ClaudeSessionFiles(paths).register("s-twin-1", "twin", live)
        ClaudeSessionFiles(paths).register("s-twin-2", "twin", live)
        codex.messageSent("s-lead", "gpt", "toolu_1")
        codex.messageSent("s-lead", "twin", "toolu_2")
        codex.messageSent("s-lead", "nobody", "toolu_3")
        codex.messageSent("s-lead", "uds:/run/peer.sock", "toolu_4")
        ClaudeSessionFiles(paths).register("s-gpt-a", "gpt", NO_PID)
        ClaudeSessionFiles(paths).register("s-gpt-b", "gpt", live)
        codex.messageSent("s-lead", "gpt", "toolu_5")
        assertEquals(
            listOf("s-gpt-a", null, null, null, "s-gpt-b"),
            await({ stores.edges.edges() }, 5).map { it.toSession },
            "two holders or none store no session, an address nobody holds included; a's ended registration holds the name no more",
        )
    }

    @Test
    fun `a socket address is stored with the one live session holding it, and none or two keep it unresolved`() {
        val paths = StatePaths(baseOverride = tmp.resolve("home/.splice/state"))
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { NOW })
        val names = ConsoleWiring.nameHolders(paths)
        val codex = ConsoleEventPublisher(stores, WallClock { NOW }, names = names).forHead("codex")
        val live = ProcessHandle.current().pid()
        ClaudeSessionFiles(paths).register("s-one", "one", live, socket = "/run/cc-socks/1.sock")
        ClaudeSessionFiles(paths).register("s-two-a", "twin-a", live, socket = "/run/cc-socks/2.sock")
        ClaudeSessionFiles(paths).register("s-two-b", "twin-b", live, socket = "/run/cc-socks/2.sock")
        ClaudeSessionFiles(paths).register("s-gone", "gone", NO_PID, socket = "/run/cc-socks/4.sock")
        for ((n, socket) in listOf(1, 2, 3, 4).withIndex()) {
            codex.messageSent("s-lead", "uds:/run/cc-socks/$socket.sock", "toolu_$n")
        }
        val stored = await({ stores.edges.edges() }, 4)
        assertEquals(
            listOf(
                RecipientResolution.Held("s-one"),
                RecipientResolution.Legacy,
                RecipientResolution.Legacy,
                RecipientResolution.Legacy,
            ),
            stored.map { it.recipient },
            "one live holder is stored; two, none, or an ended registration leave the row as it was written before",
        )
        assertEquals(listOf("s-one", null, null, null), stored.map { it.toSession })
        val rows = Files.readAllLines(tmp.resolve("activity/edges-2026-09-18.jsonl"))
        assertEquals(listOf(true, false, false, false), rows.map { "to_session" in it })
    }

    @Test
    fun `a hand-off to a socket keeps its holder after the registry forgets it`() {
        val paths = StatePaths(baseOverride = tmp.resolve("home/.splice/state"))
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { NOW })
        val names = ConsoleWiring.nameHolders(paths)
        val codex = ConsoleEventPublisher(stores, WallClock { NOW }, names = names).forHead("codex")
        val live = ProcessHandle.current().pid()
        ClaudeSessionFiles(paths).register("s-lead", "lead", live, socket = "/run/cc-socks/1.sock")
        codex.messageSent("s-builder", "uds:/run/cc-socks/1.sock", "toolu_1")
        assertEquals(1, await({ stores.edges.edges() }, 1).size)

        val teams = TeamStore(tmp.resolve("state/teams.json"), WallClock { NOW })
        val id = teams.upsert(
            Team(
                name = "atlas",
                goal = "ship",
                repo = Files.createDirectories(tmp.resolve("repo")).toString(),
                slots = listOf(
                    TeamSlot(id = "lead", role = "orchestrator", head = "claude", lead = true),
                    TeamSlot(id = "b1", role = "builder", head = "codex"),
                ),
            ),
        ).id
        teams.bind(id, mapOf("lead" to "s-lead", "b1" to "s-builder"))
        val forgotten = Files.createDirectories(tmp.resolve("forgotten"))
        val routes = TeamsRoutes(
            teams = TeamSource { teams },
            heads = emptyMap(),
            registry = SessionRegistry(
                sessionsDir = forgotten,
                routeOf = { SessionRoute.Head("claude") },
                pidAlive = { true },
                clock = { NOW },
            ),
            activity = ActivitySource { stores },
            texts = SentTextSource { _, _, ids -> SentTexts(null, emptyMap(), ids) },
            clock = WallClock { NOW },
        )
        val chat = Json.parseToJsonElement(routes.reads.chat(id, null).body).jsonObject.getValue("messages").jsonArray
        assertEquals(1, chat.size, "the hand-off still shows")
        assertEquals("lead", chat.single().jsonObject.getValue("to_slot").jsonPrimitive.content)
    }

    @Test
    fun `the control plane's publisher resolves a name against the daemon's own registry`() {
        val paths = StatePaths(baseOverride = tmp.resolve("home/.splice/state"))
        ClaudeSessionFiles(paths).register("s-gpt", "gpt", ProcessHandle.current().pid())
        val plane = ControlPlane(
            DaemonEnvironment(paths, ConfigService(paths), MgmtKey(paths), { }),
            { },
        )
        try {
            plane.console.forHead("codex").messageSent("s-lead", "gpt", "toolu_1")
            val stores = checkNotNull(plane.console.stores) { "the daemon's publisher must own the stores" }
            assertEquals(listOf("s-gpt"), await({ stores.edges.edges() }, 1).map { it.toSession })
        } finally {
            plane.cancelProbes()
        }
    }

    /** V4-444: Claude Code rewrites a registration only when its status changes, so a session busy for
     *  hours read stale on the console while this daemon served its turns. The control plane's registry
     *  hears a session through the turns its heads start. */
    @Test
    fun `the control plane's sessions read a turn its heads served as hearing from that session`() {
        val paths = StatePaths(baseOverride = tmp.resolve("heard/.splice/state"))
        val old = System.currentTimeMillis() - 12 * 3_600_000L
        ClaudeSessionFiles(paths).register("s-busy", "builder", ProcessHandle.current().pid(), updatedAt = old)
        val plane = ControlPlane(
            DaemonEnvironment(paths, ConfigService(paths), MgmtKey(paths), { }),
            { },
        )
        val srv = checkNotNull(
            runBlocking {
                plane.start(
                    controlPort = 0,
                    heads = emptyMap(),
                    failedHeads = { 0 },
                    headCount = 0,
                    probes = SilentHeadProbes,
                )
            },
        ) { "the control plane did not bind" }
        try {
            val key = MgmtKey(paths).get()
            assertEquals("stale", availability(srv.listeningPort, key, "s-busy"), "no turn yet; its file is 12 h old")
            plane.console.forHead("claudex").turnStarted("s-busy")
            assertEquals("live", availability(srv.listeningPort, key, "s-busy"), "a turn served now")
        } finally {
            srv.stop()
            plane.cancelProbes()
        }
    }

    @Test
    fun `the daemon's stores live under the state dir's activity directory, never beside the operator's trees`() {
        val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        val stores = ConsoleWiring.activityStores(statePaths, ConfigService(statePaths))
        stores.edges.record(MessageEdge("s-1", "peer", System.currentTimeMillis(), "toolu_1"))
        await({ stores.edges.edges() }, 1)
        val written = Files.list(statePaths.stateDir.resolve("activity")).use { files ->
            files.map { it.fileName.toString() }.toList()
        }
        val day = written.filterNot { it.endsWith(".lock") }
        assertEquals(1, day.size, written.toString())
        assertTrue(day.single().matches(Regex("edges-\\d{4}-\\d{2}-\\d{2}\\.jsonl")), written.toString())
    }

    @Test
    fun `head projects trees are every head's own tree in topology order, each a whole path`() {
        fun head(configDir: String?) = HeadConfig(
            provider = "p",
            port = 0,
            discoveryPrefix = "",
            pinnedModel = "m",
            claude = ClaudeWrapperConfig(configDir = configDir),
        )
        val statePaths = StatePaths(baseOverride = tmp)
        val signIn = SignInPlanner()
        val factory = LaunchSpecFactory(
            topology = Topology(heads = linkedMapOf("codex" to head(null), "grok" to head("$tmp/grok-cfg"))),
            signInPlanner = signIn,
            mgmtKey = MgmtKey(statePaths),
            buildInputs = HeadBuildInputs(ConfigService(statePaths), signIn),
        )
        assertEquals(
            listOf(
                Paths.get(System.getProperty("user.home"), ".claude-codex", "projects"),
                tmp.resolve("grok-cfg/projects"),
            ),
            factory.headProjectsTrees(),
        )
    }
}

/** The Claude Code session registry of the home [paths] lives under, where ConsoleWiring reads it. */
private class ClaudeSessionFiles(private val paths: StatePaths) {
    /** Registers [session] under [name] on [pid]. */
    fun register(
        session: String,
        name: String,
        pid: Long,
        socket: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        val dir = Files.createDirectories(checkNotNull(paths.rootDir.parent).resolve(".claude/sessions"))
        val at = socket?.let { ""","messagingSocketPath":"$it"""" }.orEmpty()
        Files.writeString(
            dir.resolve("$session.json"),
            """{"pid":$pid,"sessionId":"$session","name":"$name","updatedAt":$updatedAt$at}""",
        )
    }
}
