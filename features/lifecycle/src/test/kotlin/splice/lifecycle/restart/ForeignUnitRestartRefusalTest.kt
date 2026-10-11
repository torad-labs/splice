// `splice restart` never acts on a supervisor unit that belongs to another home: it is refused and named, and only
// the unit's own home and port restart through the unit. Driven through the verb against a loopback daemon and a
// systemctl that records every call.
package splice.lifecycle.restart

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.daemonclient.DaemonSettings
import splice.lifecycle.start.DaemonColdStart
import splice.lifecycle.start.HostSupervisedStart
import splice.lifecycle.start.ManagerEnvironment
import splice.lifecycle.start.ManagerEnvironmentBlock
import splice.lifecycle.start.SupervisedStart
import splice.lifecycle.start.Systemctl
import splice.lifecycle.start.SystemdUnitDaemon
import splice.lifecycle.start.UnitDaemon
import splice.lifecycle.start.UnitDaemonReader
import splice.lifecycle.start.UnitOwnership
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val UNIT = "splice-test.service"
private const val OLD = "old-version"
private const val NEW = "new-version"
private const val SHORT_STARTUP_POLLS = 8
private const val EVERYDAY_HOME = "/home/everyday"
private const val EVERYDAY_PORT = 3096

class ForeignUnitRestartRefusalTest {

    /** The invoking home's own daemon: answers /health with [version], lists no heads, and counts the
     *  shutdown route it must never be sent. */
    private class FakeDaemon(@Volatile var version: String) {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/health") { ex -> ex.reply("""{"ok":true,"version":"$version"}""") }
            createContext("/api/heads") { ex -> ex.reply("""{"heads":[]}""") }
            createContext("/api/daemon/shutdown") { ex ->
                shutdowns.incrementAndGet()
                ex.sendResponseHeaders(202, -1)
                ex.close()
            }
            start()
        }
        val port: Int = server.address.port
        val shutdowns = AtomicInteger()

        fun close() = server.stop(0)

        private fun HttpExchange.reply(body: String) {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    /** `cat` finds the unit, `is-active` says it runs, `restart` plays systemd; every call is kept. */
    private class FakeSystemctl(private val onRestart: () -> Unit = {}) : Systemctl {
        val calls = CopyOnWriteArrayList<List<String>>()
        override fun invoke(args: List<String>): Int {
            calls += args
            return when (args[0]) {
                "cat", "is-active" -> 0
                "restart" -> 0.also { onRestart() }
                else -> error("unexpected systemctl verb: $args")
            }
        }
    }

    private val lines = CopyOnWriteArrayList<String>()

    private fun said(): String = lines.joinToString("\n")

    /** The verb for a shell whose HOME is [home], with no harness selector set, so the route is the unit's
     *  and only the ownership check stands between it and `systemctl restart`. The unit's daemon is
     *  whatever [unitDaemon] says. */
    private fun command(home: Path, port: Int, ctl: FakeSystemctl, unitDaemon: UnitDaemonReader): RestartCommand {
        val config = Files.createDirectories(home.resolve(".config").resolve("splice")).resolve("splice.toml")
        Files.writeString(config, "[daemon]\ncontrol_port = $port\n")
        val env = EnvReader { name -> if (name == "HOME") home.toString() else null }
        val out = TerminalOutput { lines += it }
        val supervised = SupervisedStart(ctl, env, DaemonSettings(out), unitName = { UNIT }, unitDaemon = unitDaemon)
        val coldStart =
            DaemonColdStart(out, out, env, RunningJar { null }, supervised, SHORT_STARTUP_POLLS)
        return RestartCommand(out, out, env, RunningJar { null }, coldStart)
    }

    /** A refused restart signals nothing: no systemctl verb but the two that only read, no shutdown POST. */
    private fun assertNothingSignalled(ctl: FakeSystemctl, daemon: FakeDaemon) {
        val acted = ctl.calls.filter { it[0] != "cat" && it[0] != "is-active" }
        assertTrue(acted.isEmpty(), "a refused restart acted on the unit: $acted\n${said()}")
        assertEquals(0, daemon.shutdowns.get(), "a refused restart must not stop the daemon here either")
    }

    @Test
    fun `a unit whose daemon serves another control port is refused and named`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(OLD)
        val home = tmp.resolve("walk-desk")
        val ctl = FakeSystemctl()
        try {
            val restarted = command(home, daemon.port, ctl) { UnitDaemon(home, daemon.port + 1) }
                .restart(NEW, waitForCompactions = false)
            assertFalse(restarted, "a restart of another home's unit is a failed restart:\n${said()}")
            assertNothingSignalled(ctl, daemon)
            val unitAndPorts = UNIT in said() && ":${daemon.port + 1}" in said() && ":${daemon.port}" in said()
            assertTrue(unitAndPorts, "the refusal must name the unit and both ports:\n${said()}")
            assertTrue("not restarting $UNIT:" in said(), "a restart refuses as a restart:\n${said()}")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `Marlin's walk, a different HOME on a different port, is refused with both homes named`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(OLD)
        val home = tmp.resolve("walk-desk")
        val ctl = FakeSystemctl()
        try {
            val restarted = command(home, daemon.port, ctl) { UnitDaemon(Path.of(EVERYDAY_HOME), EVERYDAY_PORT) }
                .restart(NEW, waitForCompactions = false)
            assertFalse(restarted, "the everyday unit must not be restarted for walk-desk:\n${said()}")
            assertNothingSignalled(ctl, daemon)
            val named = UNIT in said() && EVERYDAY_HOME in said() && home.toString() in said()
            assertTrue(named, "the refusal must name the unit and both homes:\n${said()}")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `a different HOME alone is enough to refuse`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(OLD)
        val ctl = FakeSystemctl()
        try {
            val restarted = command(tmp.resolve("walk-desk"), daemon.port, ctl) {
                UnitDaemon(Path.of(EVERYDAY_HOME), daemon.port)
            }.restart(NEW, waitForCompactions = false)
            assertFalse(restarted, "the same port under another home is still another home's unit:\n${said()}")
            assertNothingSignalled(ctl, daemon)
            assertTrue(EVERYDAY_HOME in said(), "the refusal must name the unit's home:\n${said()}")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `a unit whose environment cannot be read is refused rather than guessed at`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(OLD)
        val ctl = FakeSystemctl()
        try {
            val restarted = command(tmp.resolve("walk-desk"), daemon.port, ctl) { null }
                .restart(NEW, waitForCompactions = false)
            assertFalse(restarted, "an unproven owner must not be restarted:\n${said()}")
            assertNothingSignalled(ctl, daemon)
            assertTrue(UNIT in said() && "could not be read" in said(), "the refusal must say why:\n${said()}")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `the unit's own home and port still restart through the unit`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(OLD)
        val ctl = FakeSystemctl(onRestart = { daemon.version = NEW })
        val home = tmp.resolve("everyday")
        try {
            val restarted = command(home, daemon.port, ctl) { UnitDaemon(home, daemon.port) }
                .restart(NEW, waitForCompactions = false)
            assertTrue(restarted, "the unit's own home must restart through it:\n${said()}")
            assertTrue(listOf("restart", UNIT) in ctl.calls, "systemd was never asked to restart: ${ctl.calls}")
            assertEquals(0, daemon.shutdowns.get(), "systemd stops the unit's daemon; the verb must not")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `the manager's environment block parses plain and dollar-quoted values`() {
        val env = ManagerEnvironment.parse(
            "HOME=/home/marcos\nPATH=/usr/bin:/bin\nSPACED=\$'/home/a b'\nQUOTED=\$'it\\'s'\nNOEQUALS\n=orphan\n",
        )
        assertEquals("/home/marcos", env["HOME"])
        assertEquals("/usr/bin:/bin", env["PATH"])
        assertEquals("/home/a b", env["SPACED"])
        assertEquals("it's", env["QUOTED"])
        assertEquals(4, env.size, "a line with no name or no equals sign is not a variable: $env")
    }

    /** The reader over a manager whose environment block is [block]: no host manager, no host home. */
    private fun readerOver(block: String?): UnitDaemonReader =
        SystemdUnitDaemon(DaemonSettings(TerminalOutput { lines += it }), ManagerEnvironmentBlock { block })

    @Test
    fun `a unit's daemon is read from the manager's HOME and that home's splice toml`(@TempDir tmp: Path) {
        val home = tmp.resolve("unit-home")
        val config = Files.createDirectories(home.resolve(".config").resolve("splice")).resolve("splice.toml")
        Files.writeString(config, "[daemon]\ncontrol_port = 4321\n")
        assertEquals(UnitDaemon(home, 4321), readerOver("HOME=$home\nPATH=/usr/bin\n")(UNIT))
    }

    @Test
    fun `a unit's home with no splice toml is read as the default port and never written into`(@TempDir tmp: Path) {
        val home = tmp.resolve("unit-home")
        val env = EnvReader { name -> if (name == "HOME") home.toString() else null }
        val daemon = readerOver("HOME=$home\n")(UNIT)
        assertEquals(UnitDaemon(home, DaemonSettings(TerminalOutput { }).controlPort(null, env)), daemon)
        assertTrue(Files.notExists(home), "reading another home's daemon must never create files there")
    }

    @Test
    fun `a manager block that cannot be trusted reads as no daemon`(@TempDir tmp: Path) {
        val home = Files.createDirectories(tmp.resolve("unit-home").resolve(".config").resolve("splice"))
        Files.writeString(home.resolve("splice.toml"), "[daemon\ncontrol_port = = 4321")
        val unreadable = "HOME=${tmp.resolve("unit-home")}\n"
        assertEquals(null, readerOver(unreadable)(UNIT), "a splice.toml that does not parse cannot name a port")
        assertEquals(null, readerOver("PATH=/usr/bin\n")(UNIT), "no HOME in the block must not fall back to ours")
        assertEquals(null, readerOver(null)(UNIT), "no manager answered")
    }

    @Test
    fun `the shipped wiring checks ownership, so a home no unit manager runs is refused on any host`(
        @TempDir tmp: Path,
    ) {
        val env = EnvReader { name -> if (name == "HOME") tmp.toString() else null }
        val ownership = HostSupervisedStart.of(env, TerminalOutput { lines += it }).ownership(UNIT, EVERYDAY_PORT)
        // A manager that answers runs some other HOME than this temp dir, and none answering is unreadable:
        // both are foreign, and only a wiring with no reader says the unit is ours.
        assertTrue(ownership is UnitOwnership.Foreign, "the shipped wiring left the ownership check off: $ownership")
    }
}
