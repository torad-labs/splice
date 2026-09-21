// NEW: V4-190 — the CLI cold start is unit-first. RED on the pre-fix DaemonLaunch: with the unit on the
// box and no selector set, ensureDaemon raw-spawned (the "starting the daemon" line) and never asked
// systemctl. The selector list is pinned to the launch shim's unitDefaults() so the shim and the
// CLI cannot disagree about which shells own their daemon.
package splice.app.cli.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

private const val SHIM = "app/src/main/dist/bin/splice-launch"
private const val UNIT = "splice.service"
private const val CANARY_UNIT = "splice-canary.service"

class SupervisedStartTest {

    /** A systemctl that records every call; `cat` answers [unitPresent], `start` answers [startOk]. */
    private class FakeSystemctl(
        private val unitPresent: Boolean = true,
        private val startOk: Boolean = true,
    ) : Systemctl {
        /** Every call, as the real port receives it: the args after `systemctl --user`. */
        val calls = mutableListOf<List<String>>()
        override fun invoke(args: List<String>): Int {
            calls += args
            val ok = when (args[0]) {
                "cat" -> unitPresent
                "start" -> startOk
                else -> error("unexpected verb: $args")
            }
            return if (ok) 0 else 1
        }
    }

    private fun env(vararg pairs: Pair<String, String>): EnvReader = EnvReader { pairs.toMap()[it] }

    @Test
    fun `no selector and a unit on the box routes to the unit, by the configured name`() {
        val ctl = FakeSystemctl()
        assertEquals(ColdStartRoute.Unit(CANARY_UNIT), SupervisedStart(ctl, env()).route(CANARY_UNIT))
        assertEquals(listOf(listOf("cat", CANARY_UNIT)), ctl.calls)
    }

    @Test
    fun `every harness selector routes to the raw spawn without asking systemctl, whitespace included`() {
        for (selector in harnessSelectors) {
            for (value in listOf("/x", " ")) {
                val ctl = FakeSystemctl()
                val route = SupervisedStart(ctl, env(selector to value)).route(UNIT)
                assertTrue(route is ColdStartRoute.Raw && selector in route.reason, "$selector=$value -> $route")
                assertTrue(ctl.calls.isEmpty(), "$selector set must never touch systemctl")
            }
        }
        // An EMPTY selector is unset, as the shim's ${X:-} reads it.
        val emptySelector = SupervisedStart(FakeSystemctl(), env("SPLICE_CONFIG" to "")).route(UNIT)
        assertEquals(ColdStartRoute.Unit(UNIT), emptySelector)
    }

    @Test
    fun `no unit on the box routes to the raw spawn`() {
        val route = SupervisedStart(FakeSystemctl(unitPresent = false), env()).route(UNIT)
        assertTrue(route is ColdStartRoute.Raw && UNIT in route.reason, route.toString())
    }

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

    // ── DaemonLaunch, the composer ────────────────────────────────────────────────────────────

    /** A spawn that must never happen: records the attempt, starts nothing. */
    private class RecordingSpawn(health: DaemonHealth) : DaemonSpawn(health) {
        var spawns = 0
        override fun startableJar(port: Int): Path? = Path.of("/nonexistent/splice.jar")
        override fun spawnDaemon(argv: List<String>): Boolean {
            spawns++
            return false
        }
        override fun printBootLogTail() = Unit
        override fun logsDir(): Path = Files.createTempDirectory("splice-launch-test")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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
    fun `with the unit on the box the CLI starts it, waits, and never spawns beside it`() {
        val ctl = FakeSystemctl()
        val health = DaemonHealth()
        val spawn = RecordingSpawn(health)
        val launch = DaemonLaunch(health, spawn, SupervisedStart(ctl, env()), startupPolls = 2)
        var up = true
        val out = captured { up = launch.ensureDaemon(freePort()) }
        assertFalse(up, "nothing answers on a free port, so the start is reported failed")
        assertEquals(0, spawn.spawns, "a second daemon was spawned beside the unit:\n$out")
        val started = listOf("start", UNIT) in ctl.calls
        assertTrue(started, "the unit was never started: ${ctl.calls}\n$out")
        val named = "did not answer" in out && "journalctl --user -u $UNIT" in out
        assertTrue(named, "the failure must name the journal:\n$out")
    }

    @Test
    fun `a selector override or no unit takes the raw spawn, and systemctl start is never run`() {
        val arms = listOf(
            FakeSystemctl() to env("SPLICE_STATE_DIR" to "/tmp/x"),
            FakeSystemctl(unitPresent = false) to env(),
        )
        for ((ctl, reader) in arms) {
            val health = DaemonHealth()
            val spawn = RecordingSpawn(health)
            val launch = DaemonLaunch(health, spawn, SupervisedStart(ctl, reader), startupPolls = 1)
            val out = captured { assertFalse(launch.ensureDaemon(freePort())) }
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

    private fun repo(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("install.sh")) && dir.parent != null) dir = dir.parent
        return dir
    }
}
