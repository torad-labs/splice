// NEW: V4-190 — the CLI cold start is unit-first. RED on the pre-fix DaemonLaunch: with the unit on the
// box and no selector set, ensureDaemon raw-spawned (the "starting the daemon" line) and never asked
// systemctl. The selector list is pinned to the launch shim's unitDefaults() so the shim and the
// CLI cannot disagree about which shells own their daemon.
package splice.lifecycle.start

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.daemonclient.DaemonHealth
import splice.daemonclient.DaemonSettings
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

private const val SHIM = "app/src/main/dist/bin/splice-launch"
private const val UNIT = "splice.service"
private const val CANARY_UNIT = "splice-canary.service"

class SupervisedStartTest {

    /** A systemctl that records every call; `cat` answers [unitPresent], `start` answers [startOk],
     *  `is-active` answers [active]. */
    private class FakeSystemctl(
        private val unitPresent: Boolean = true,
        private val startOk: Boolean = true,
        private val active: Boolean = false,
    ) : Systemctl {
        /** Every call, as the real port receives it: the args after `systemctl --user`. */
        val calls = mutableListOf<List<String>>()
        override fun invoke(args: List<String>): Int {
            calls += args
            val ok = when (args[0]) {
                "cat" -> unitPresent
                "start" -> startOk
                "is-active" -> active
                else -> error("unexpected verb: $args")
            }
            return if (ok) 0 else 1
        }
    }

    private fun env(vararg pairs: Pair<String, String>): EnvReader = EnvReader { pairs.toMap()[it] }

    /** The launch's lines reach System.out, which [captured] reads. */
    private val out = TerminalOutput(::println)

    /** The supervisor-unit read's corrupt-TOML diagnostic, if a test ever reached one. */
    private val settings = DaemonSettings(TerminalOutput(System.err::println))

    @Test
    fun `no selector and a unit on the box routes to the unit, by the configured name`() {
        val ctl = FakeSystemctl()
        assertEquals(ColdStartRoute.Unit(CANARY_UNIT), SupervisedStart(ctl, env(), settings).route(CANARY_UNIT))
        assertEquals(listOf(listOf("cat", CANARY_UNIT)), ctl.calls)
    }

    @Test
    fun `every harness selector routes to the raw spawn without asking systemctl, whitespace included`() {
        for (selector in harnessSelectors) {
            for (value in listOf("/x", " ")) {
                val ctl = FakeSystemctl()
                val route = SupervisedStart(ctl, env(selector to value), settings).route(UNIT)
                assertTrue(route is ColdStartRoute.Raw && selector in route.reason, "$selector=$value -> $route")
                assertTrue(ctl.calls.isEmpty(), "$selector set must never touch systemctl")
            }
        }
        // An EMPTY selector is unset, as the shim's ${X:-} reads it.
        val emptySelector = SupervisedStart(FakeSystemctl(), env("SPLICE_CONFIG" to ""), settings).route(UNIT)
        assertEquals(ColdStartRoute.Unit(UNIT), emptySelector)
    }

    @Test
    fun `no unit on the box routes to the raw spawn`() {
        val route = SupervisedStart(FakeSystemctl(unitPresent = false), env(), settings).route(UNIT)
        assertTrue(route is ColdStartRoute.Raw && UNIT in route.reason, route.toString())
    }

    // ── DaemonLaunch, the composer ────────────────────────────────────────────────────────────

    /** A spawn that must never happen: records the attempt, starts nothing. */
    private class RecordingSpawn(health: DaemonHealth, private val logs: Path) :
        DaemonSpawn(TerminalOutput(::println), health, RunningJar { null }) {
        var spawns = 0
        override fun startableJar(port: Int): Path? = Path.of("/nonexistent/splice.jar")
        override fun spawnDaemon(argv: List<String>): Boolean {
            spawns++
            return false
        }
        override fun printBootLogTail() = Unit
        override fun logsDir(): Path = logs
    }

    private fun captured(block: () -> Unit): String {
        val out = ByteArrayOutputStream()
        val prior = System.out
        System.setOut(PrintStream(out, true))
        try {
            block()
        } finally {
            System.setOut(prior)
        }
        return out.toString()
    }

    @Test
    fun `with the unit on the box the CLI starts it, waits, and never spawns beside it`(@TempDir logs: Path) {
        val ctl = FakeSystemctl()
        val health = DaemonHealth()
        val spawn = RecordingSpawn(health, logs)
        val launch = DaemonLaunch(out, health, spawn, SupervisedStart(ctl, env(), settings), startupPolls = 2)
        var up = true
        val out = captured { up = launch.ensureDaemon(TestPorts.reserve()) }
        assertFalse(up, "nothing answers on a free port, so the start is reported failed")
        assertEquals(0, spawn.spawns, "a second daemon was spawned beside the unit:\n$out")
        val started = listOf("start", UNIT) in ctl.calls
        assertTrue(started, "the unit was never started: ${ctl.calls}\n$out")
        val named = "did not answer" in out && "journalctl --user -u $UNIT" in out
        assertTrue(named, "the failure must name the journal:\n$out")
    }

    // Oct 1, 11:49 PM CT: splice.service was restarting after a failed boot, a `splice start` typed in a
    // terminal ran the daemon there, and the unit then lost its port to it. The unit a start goes through
    // is named whether or not it runs; only a selector or a box without a unit keeps the daemon in the shell.
    @Test
    fun `a start goes through the unit even while it is down, and a selector or no unit keeps it here`(
        @TempDir logs: Path,
    ) {
        val launch = { ctl: FakeSystemctl, reader: EnvReader ->
            val health = DaemonHealth()
            val supervised = SupervisedStart(ctl, reader, settings, unitName = SupervisorUnitName { UNIT })
            DaemonLaunch(out, health, RecordingSpawn(health, logs), supervised)
        }
        val down = launch(FakeSystemctl(active = false), env())
        assertEquals(UNIT, down.supervisorUnit(), "a unit that is down is still the one a start goes through")
        assertEquals(null, down.activeUnit(), "a restart still reads a down unit as running no daemon")
        assertEquals(null, launch(FakeSystemctl(), env("SPLICE_STATE_DIR" to "/tmp/x")).supervisorUnit())
        assertEquals(null, launch(FakeSystemctl(unitPresent = false), env()).supervisorUnit())
    }

    // The verb itself, as Main dispatches it: a unit that is down is started and waited on, never replaced by a
    // daemon in this process; only a selector or a box without a unit answers null, and nothing is started.
    @Test
    fun `splice start goes through a unit that is down, and a selector or no unit starts nothing there`() {
        val coldStart = { ctl: FakeSystemctl, reader: EnvReader ->
            val supervised = SupervisedStart(ctl, reader, settings, unitName = SupervisorUnitName { UNIT })
            DaemonColdStart(out, out, reader, RunningJar { null }, supervised, startupPolls = 2)
        }
        val down = FakeSystemctl(active = false)
        var code: Int? = null
        val said = captured { code = coldStart(down, env()).startThroughUnit(TestPorts.reserve()) }
        assertEquals(1, code, "nothing answers on a free port, so the start through the unit fails:\n$said")
        assertTrue(listOf("start", UNIT) in down.calls, "the down unit was never started: ${down.calls}")
        val ownDaemon = listOf(
            FakeSystemctl() to env("SPLICE_STATE_DIR" to "/tmp/x"),
            FakeSystemctl(unitPresent = false) to env(),
        )
        for ((ctl, reader) in ownDaemon) {
            val code = coldStart(ctl, reader).startThroughUnit(TestPorts.reserve())
            assertEquals(null, code, "this shell's daemon is its own")
            assertTrue(ctl.calls.none { it[0] == "start" }, "a unit was started for a shell with its own: ${ctl.calls}")
        }
    }

    // A shell outside a login session has neither variable systemctl --user needs, and every user verb failed as if
    // there were no unit (reproduced Oct 2: "Failed to connect to user scope bus", exit 1; with only
    // XDG_RUNTIME_DIR set, exit 0). The manager's runtime directory is supplied to the child; a shell's own stands.
    @Test
    fun `a shell with no user bus reaches the user manager through its runtime directory`(@TempDir root: Path) {
        Files.createDirectory(root.resolve("4242"))
        fun supplied(vararg pairs: Pair<String, String>, uid: Int? = 4242): String? {
            val builder = ProcessBuilder("true").also { it.environment().clear() }
            builder.environment().putAll(pairs)
            return UserManagerBus.supply(builder, root, uid).environment()["XDG_RUNTIME_DIR"]
        }
        assertEquals(root.resolve("4242").toString(), supplied(), "no bus in the shell: the manager's runtime dir")
        assertEquals("/run/user/7", supplied("XDG_RUNTIME_DIR" to "/run/user/7"), "a shell's own runtime dir stands")
        assertEquals(null, supplied("DBUS_SESSION_BUS_ADDRESS" to "unix:path=/x/bus"), "a shell's own bus stands")
        assertEquals(null, supplied(uid = 4343), "no runtime directory for this uid: nothing to supply")
        assertEquals(null, supplied(uid = null), "no uid (no /proc): nothing to supply")
    }

    @Test
    fun `a selector override or no unit takes the raw spawn, and systemctl start is never run`(@TempDir logs: Path) {
        val arms = listOf(
            FakeSystemctl() to env("SPLICE_STATE_DIR" to "/tmp/x"),
            FakeSystemctl(unitPresent = false) to env(),
        )
        for ((ctl, reader) in arms) {
            val health = DaemonHealth()
            val spawn = RecordingSpawn(health, logs)
            val launch = DaemonLaunch(out, health, spawn, SupervisedStart(ctl, reader, settings), startupPolls = 1)
            val out = captured { assertFalse(launch.ensureDaemon(TestPorts.reserve())) }
            assertEquals(1, spawn.spawns, "the raw spawn is still the cold start here:\n$out")
            assertTrue(ctl.calls.none { it[0] == "start" }, "the unit must not be started: ${ctl.calls}")
        }
    }

    @Test
    fun `the real systemctl port answers non-zero for a manager it cannot reach, never throws`() {
        // PATH-independent: an executable name that exists nowhere is exactly "no systemctl here".
        val code = JdkSystemctl().let { port ->
            // The port always prefixes `systemctl --user`; drive it with a verb no manager accepts
            // so a present manager also answers non-zero, and an absent one answers 127.
            port(listOf("cat", "splice-test-unit-that-does-not-exist-${System.nanoTime()}.service"))
        }
        assertTrue(code != 0, "expected a non-zero answer, got $code")
    }
}

/** The selectors the CLI reads and the launch shim reads are one list: a selector added to one and not the other is the drift
 *  this law exists to catch. It reads the shim's text, so it is a law (`lawTest`), and a class of its own, never split. */
@Tag("law")
class ShimSelectorListLawTest {

    @Test
    fun `the selector list is the shim's unitDefaults list, byte for byte`() {
        val shim = repo().resolve(SHIM).readText()
        // The `selectors` array literal: `env.NAME` entries, then the captured CONTROL_PORT_FROM_ENV.
        val list = shim.substringAfter("function unitDefaults() {").substringBefore("];")
        val shimSelectors = Regex("""\b(?:env\.)?([A-Z_]+)\b""").findAll(list.substringAfter("["))
            .map { it.groupValues[1].removeSuffix("_FROM_ENV") }
            .toList()
        assertEquals(shimSelectors, harnessSelectors, "$SHIM unitDefaults() and the CLI's list drifted")
    }

    private fun repo(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("install.sh")) && dir.parent != null) dir = dir.parent
        return dir
    }
}
