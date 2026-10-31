// The cold start never starts a supervisor unit that belongs to another home: it refuses with the restart's sentence
// and starts nothing, while the unit's own home and port start through it. Driven through DaemonColdStart against a
// systemctl that records every verb.
package splice.lifecycle.start

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
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

private const val UNIT = "splice-test.service"
private const val OLD = "old-version"
private const val NEW = "new-version"
private const val SHORT_STARTUP_POLLS = 8
private const val EVERYDAY_HOME = "/home/everyday"
private const val EVERYDAY_PORT = 3096

class ForeignUnitStartRefusalTest {

    /** The invoking home's daemon, or the stand-in for one the unit will bring up: answers /health with [version]. */
    private class FakeDaemon(@Volatile var version: String) {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/health") { ex -> ex.reply("""{"ok":true,"version":"$version"}""") }
            start()
        }
        val port: Int = server.address.port

        fun close() = server.stop(0)

        private fun HttpExchange.reply(body: String) {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    /** `cat` finds the unit, `is-active` says it is down, `start` plays systemd; every call is kept. */
    private class FakeSystemctl(private val onStart: () -> Unit = {}) : Systemctl {
        val calls = CopyOnWriteArrayList<List<String>>()
        override fun invoke(args: List<String>): Int {
            calls += args
            return when (args[0]) {
                "cat" -> 0
                "is-active" -> 3
                "start" -> 0.also { onStart() }
                else -> error("unexpected systemctl verb: $args")
            }
        }
    }

    private val lines = CopyOnWriteArrayList<String>()

    private fun said(): String = lines.joinToString("\n")

    /** The cold start for a shell whose HOME is [home], with no harness selector set, so the route is the unit's
     *  and only the ownership check stands between it and `systemctl start`. */
    private fun coldStart(home: Path, ctl: FakeSystemctl, unitDaemon: UnitDaemonReader): DaemonColdStart {
        val env = EnvReader { name -> if (name == "HOME") home.toString() else null }
        val out = TerminalOutput { lines += it }
        val supervised = SupervisedStart(ctl, env, DaemonSettings(out), unitName = { UNIT }, unitDaemon = unitDaemon)
        return DaemonColdStart(out, out, env, RunningJar { null }, supervised, SHORT_STARTUP_POLLS)
    }

    /** A refused start runs no verb that acts on the unit: only the two that read may have been asked. */
    private fun assertUnitUntouched(ctl: FakeSystemctl) {
        val acted = ctl.calls.filter { it[0] != "cat" && it[0] != "is-active" }
        assertTrue(acted.isEmpty(), "a refused start acted on the unit: $acted\n${said()}")
    }

    /** The refusal is V4-395's sentence with the verb of what was refused (a start, not a restart), printed once. */
    private fun assertRefusedInTheRestartWords(cold: DaemonColdStart, port: Int) {
        val restart = requireNotNull(cold.foreignUnitRefusal(port)) { "the unit read as this home's" }
        assertTrue("not restarting $UNIT:" in restart, "a restart says what it refused: $restart")
        val sentence = restart.replace("not restarting", "not starting")
        assertEquals(listOf(sentence), lines.toList(), "one refusal, the restart's words with a start's verb")
    }

    @Test
    fun `a scratch home starting the everyday unit is refused and starts nothing`(@TempDir tmp: Path) {
        val ctl = FakeSystemctl()
        val cold = coldStart(tmp.resolve("walk-desk"), ctl) { UnitDaemon(Path.of(EVERYDAY_HOME), EVERYDAY_PORT) }
        val port = FakeDaemon(OLD).also { it.close() }.port

        val up = cold.ensureDaemon(port, NEW)

        assertFalse(up, "another home's unit must not be started for this shell:\n${said()}")
        assertUnitUntouched(ctl)
        assertRefusedInTheRestartWords(cold, port)
        assertTrue(EVERYDAY_HOME in said() && tmp.resolve("walk-desk").toString() in said(), said())
    }

    @Test
    fun `a unit serving another control port is refused`(@TempDir tmp: Path) {
        val ctl = FakeSystemctl()
        val home = tmp.resolve("walk-desk")
        val port = FakeDaemon(OLD).also { it.close() }.port
        val cold = coldStart(home, ctl) { UnitDaemon(home, port + 1) }

        assertFalse(cold.ensureDaemon(port, NEW), said())
        assertUnitUntouched(ctl)
        assertRefusedInTheRestartWords(cold, port)
    }

    @Test
    fun `a different HOME alone is enough to refuse`(@TempDir tmp: Path) {
        val ctl = FakeSystemctl()
        val port = FakeDaemon(OLD).also { it.close() }.port
        val cold = coldStart(tmp.resolve("walk-desk"), ctl) { UnitDaemon(Path.of(EVERYDAY_HOME), port) }

        assertFalse(cold.ensureDaemon(port, NEW), said())
        assertUnitUntouched(ctl)
        assertRefusedInTheRestartWords(cold, port)
    }

    @Test
    fun `a unit whose environment cannot be read is refused rather than guessed at`(@TempDir tmp: Path) {
        val ctl = FakeSystemctl()
        val port = FakeDaemon(OLD).also { it.close() }.port
        val cold = coldStart(tmp.resolve("walk-desk"), ctl) { null }

        assertFalse(cold.ensureDaemon(port, NEW), said())
        assertUnitUntouched(ctl)
        assertRefusedInTheRestartWords(cold, port)
        assertTrue("could not be read" in said(), said())
    }

    @Test
    fun `the unit's own home and port still start through the unit`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(OLD)
        val ctl = FakeSystemctl(onStart = { daemon.version = NEW })
        val home = Files.createDirectories(tmp.resolve("everyday"))
        try {
            val up = coldStart(home, ctl) { UnitDaemon(home, daemon.port) }.ensureDaemon(daemon.port, NEW)

            assertTrue(up, "the unit's own home must start through it:\n${said()}")
            assertTrue(listOf("start", UNIT) in ctl.calls, "systemd was never asked to start: ${ctl.calls}")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `a daemon already answering with the expected version is left alone and asks nothing of the unit`(
        @TempDir tmp: Path,
    ) {
        val daemon = FakeDaemon(NEW)
        val ctl = FakeSystemctl()
        try {
            val up = coldStart(tmp.resolve("walk-desk"), ctl) { UnitDaemon(Path.of(EVERYDAY_HOME), EVERYDAY_PORT) }
                .ensureDaemon(daemon.port, NEW)

            assertTrue(up, said())
            assertEquals(emptyList<List<String>>(), ctl.calls.toList(), "a running daemon needs no route")
            assertEquals(emptyList<String>(), lines.toList())
        } finally {
            daemon.close()
        }
    }
}
