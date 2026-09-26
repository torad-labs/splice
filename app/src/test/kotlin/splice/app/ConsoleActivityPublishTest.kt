// NEW: V4-130 — the daemon side of the console's activity stores. ConsoleEventPublisher is the one
// writer: a head's SendMessage edge becomes an edge row (and a message.edge frame, pinned by
// ConsoleEventProducersTest), its local label and its near-miss label query become activity rows
// under that head, and a fact with no session is stored nowhere. And LaunchSpecFactory.headProjectsTrees,
// the head trees SessionProject's headless fallback searches before its vanilla default: every served
// head's projects tree, each a whole path.
//
// THE WIRING PINS. Four production lines join these pieces and the compiler checks none of them,
// because each has a default so tests can build the classes bare: ControlPlane hands the route the
// publisher's stores and the publisher its name holders over the daemon's session registry (both
// checked behaviourally on a real ControlPlane, the OneEventBusPinTest idiom), and Daemon builds ONE
// SessionProject over the head trees and hands it to HeadServerFactory (pinned on the source text,
// the ConsoleWiringPinTest idiom).
//
// V4-252: a SendMessage name is stored with the one live session that held it then, so a board read
// after the name moved files the call where it went (MessageEdgeStore's header).
package splice.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.SignInPlanner
import splice.app.control.DashboardPage
import splice.app.control.TurnPathStalled
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

    /** Registers [session] under [name] on [pid] in the Claude Code registry of the home [paths] lives
     *  under, where ConsoleWiring reads it. */
    private fun register(paths: StatePaths, session: String, name: String, pid: Long) {
        val dir = Files.createDirectories(checkNotNull(paths.rootDir.parent).resolve(".claude/sessions"))
        Files.writeString(
            dir.resolve("$session.json"),
            """{"pid":$pid,"sessionId":"$session","name":"$name","updatedAt":${System.currentTimeMillis()}}""",
        )
    }

    @Test
    fun `a SendMessage name is stored with the one live session holding it, and keeps it after the name moves`() {
        val paths = StatePaths(baseOverride = tmp.resolve("home/.splice/state"))
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { NOW })
        val names = ConsoleWiring.nameHolders(paths)
        val codex = ConsoleEventPublisher(stores, WallClock { NOW }, names = names).forHead("codex")
        val live = ProcessHandle.current().pid()
        register(paths, "s-gpt-a", "gpt", live)
        register(paths, "s-twin-1", "twin", live)
        register(paths, "s-twin-2", "twin", live)
        codex.messageSent("s-lead", "gpt", "toolu_1")
        codex.messageSent("s-lead", "twin", "toolu_2")
        codex.messageSent("s-lead", "nobody", "toolu_3")
        codex.messageSent("s-lead", "uds:/run/peer.sock", "toolu_4")
        register(paths, "s-gpt-a", "gpt", NO_PID)
        register(paths, "s-gpt-b", "gpt", live)
        codex.messageSent("s-lead", "gpt", "toolu_5")
        assertEquals(
            listOf("s-gpt-a", null, null, null, "s-gpt-b"),
            await({ stores.edges.edges() }, 5).map { it.toSession },
            "two holders, none, or an address store no session; a's ended registration holds the name no more",
        )
    }

    @Test
    fun `the control plane's publisher resolves a name against the daemon's own registry`() {
        val paths = StatePaths(baseOverride = tmp.resolve("home/.splice/state"))
        register(paths, "s-gpt", "gpt", ProcessHandle.current().pid())
        val plane = ControlPlane(
            paths,
            ConfigService(paths),
            MgmtKey(paths),
            DashboardPage { "<!doctype html>" },
            { },
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
    fun `the control plane's sessions routes read the same stores its heads write`() {
        val paths = StatePaths(baseOverride = tmp.resolve("plane-state"))
        val plane = ControlPlane(
            paths,
            ConfigService(paths),
            MgmtKey(paths),
            DashboardPage { "<!doctype html>" },
            { },
            { },
        )
        val srv = checkNotNull(
            runBlocking {
                plane.start(
                    controlPort = 0, // OS-assigned at bind: no leased port to lose before the bind
                    heads = emptyMap(),
                    failedHeads = { 0 },
                    headCount = 0,
                    turnPathStalled = TurnPathStalled { emptyList() },
                )
            },
        ) { "the control plane did not bind" }
        try {
            assertNotNull(plane.console.stores, "the daemon's publisher must own the stores")
            assertSame(plane.console.stores, srv.ports.activity, "the routes must read the stores the heads write")
        } finally {
            srv.stop()
            plane.cancelProbes()
        }
    }

    @Test
    fun `the daemon builds one session project over the head trees and gives it to every head`() {
        val daemon = source("app/src/main/kotlin/splice/app/Daemon.kt")
        assertTrue(
            daemon.contains("SessionProject(headProjectsDirs = launchSpecFactory.headProjectsTrees())"),
            "Daemon must build SessionProject over the head trees, or headless head sessions never resolve",
        )
        assertTrue(
            // V4-160 folded HeadServerFactory's configDir, projects and sessionProject into one
            // HeadPromptInputs — all three feed only the prompt layers. The pin follows the value,
            // not the spelling: what must not regress is that the SessionProject built above reaches
            // the factory, whose default reads the vanilla tree only.
            daemon.contains("HeadPromptInputs(topologyDir, topology.projects, sessionProject)"),
            "Daemon must hand that SessionProject to HeadServerFactory, whose default reads the vanilla tree only",
        )
    }

    private fun source(relative: String): String {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("$relative not found above the working directory")
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
