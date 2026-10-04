// NEW: V4-243 — `splice restart` restarts the unit's own daemon THROUGH the unit. RED on the pre-fix
// verb: it POSTed the daemon's shutdown, and once the ports were free it ran `systemctl start` on a
// unit still active in its shutdown tail, a no-op; the daemon's exit then waited out the unit's
// restart backoff (46 s at the sixth automatic restart, 2026-09-25 18:15:32 to 18:16:18 CDT).
// Driven through the verb against a loopback daemon and a systemctl that records every call and
// plays systemd's part: `restart` brings up the new version, `start` serves it again after a stop.
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
import splice.lifecycle.start.SupervisedStart
import splice.lifecycle.start.Systemctl
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val UNIT = "splice-test.service"
private const val OLD = "old-version"
private const val NEW = "new-version"
private const val SHORT_STARTUP_POLLS = 8

/** `systemctl is-active` for a unit that is not running. */
private const val INACTIVE = 3

class RestartThroughUnitTest {

    /** A daemon that answers /health with [version], lists no heads, and stops listening when its
     *  shutdown route is called, as a stopping daemon frees its port. [serveAgain] is the new
     *  process a unit start brings up on the same port. */
    private class FakeDaemon(@Volatile var version: String) {
        private var server: HttpServer = serve(0)
        val port: Int = server.address.port
        val shutdowns = AtomicInteger()

        private fun serve(on: Int): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", on), 0).apply {
            createContext("/health") { ex -> ex.reply("""{"ok":true,"version":"$version"}""") }
            createContext("/api/heads") { ex -> ex.reply("""{"heads":[]}""") }
            createContext("/api/daemon/shutdown") { ex ->
                shutdowns.incrementAndGet()
                ex.sendResponseHeaders(202, -1)
                ex.close()
                Thread { stop(0) }.start()
            }
            start()
        }

        fun serveAgain(newVersion: String) {
            version = newVersion
            server = serve(port)
        }

        fun close() = server.stop(0)

        private fun HttpExchange.reply(body: String) {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    /** `cat` finds the unit; `is-active` answers [active]; `restart` and `start` play systemd. */
    private class FakeSystemctl(
        private val active: Boolean,
        private val restartOk: Boolean = true,
        private val onRestart: () -> Unit = {},
        private val onStart: () -> Unit = {},
    ) : Systemctl {
        val calls = CopyOnWriteArrayList<List<String>>()
        override fun invoke(args: List<String>): Int {
            calls += args
            return when (args[0]) {
                "cat" -> 0
                "is-active" -> isActive()
                "restart" -> restart()
                "start" -> 0.also { onStart() }
                else -> error("unexpected systemctl verb: $args")
            }
        }

        private fun isActive(): Int = if (active) 0 else INACTIVE

        private fun restart(): Int = if (restartOk) 0.also { onRestart() } else 1
    }

    private val lines = CopyOnWriteArrayList<String>()

    /** The verb, reading its port from a temp config and its mgmt key from a temp state dir, with a
     *  cold start whose systemctl is [ctl] and whose unit is [UNIT]: no host config, no real unit. */
    private fun command(tmp: Path, port: Int, ctl: FakeSystemctl): RestartCommand {
        val stateDir = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(stateDir.resolve("mgmt-key"), "test-mgmt-key")
        val config = tmp.resolve("splice.toml")
        Files.writeString(config, "[daemon]\ncontrol_port = $port\n")
        val env = EnvReader { name ->
            when (name) {
                "CLAUDEX_STATE_DIR" -> stateDir.toString()
                "SPLICE_CONFIG" -> config.toString()
                else -> null
            }
        }
        val out = TerminalOutput { lines += it }
        val supervised = SupervisedStart(ctl, EnvReader { null }, DaemonSettings(out), unitName = { UNIT })
        val coldStart =
            DaemonColdStart(out, out, EnvReader { null }, RunningJar { null }, supervised, SHORT_STARTUP_POLLS)
        return RestartCommand(out, out, env, RunningJar { null }, coldStart)
    }

    @Test
    fun `the unit's own daemon is restarted through the unit, never stopped here and the unit started`(
        @TempDir tmp: Path,
    ) {
        val daemon = FakeDaemon(OLD)
        val ctl = FakeSystemctl(active = true, onRestart = { daemon.version = NEW })
        try {
            val restarted = command(tmp, daemon.port, ctl).restart(NEW, waitForCompactions = false)
            assertTrue(restarted, "the restart must report the new daemon up:\n${lines.joinToString("\n")}")
            assertTrue(listOf("restart", UNIT) in ctl.calls, "systemd was never asked to restart: ${ctl.calls}")
            assertFalse(ctl.calls.any { it[0] == "start" }, "a start on the unit's own daemon: ${ctl.calls}")
            assertEquals(0, daemon.shutdowns.get(), "systemd stops the unit's daemon; the verb must not")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `a daemon the unit is not running is stopped here and the unit started, as before`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(OLD)
        val ctl = FakeSystemctl(active = false, onStart = { daemon.serveAgain(NEW) })
        try {
            val restarted = command(tmp, daemon.port, ctl).restart(NEW, waitForCompactions = false)
            assertTrue(restarted, "the unit's start must bring the new daemon up:\n${lines.joinToString("\n")}")
            assertEquals(1, daemon.shutdowns.get(), "a daemon outside the unit is stopped by the verb")
            assertTrue(listOf("start", UNIT) in ctl.calls, "the unit was never started: ${ctl.calls}")
            assertFalse(ctl.calls.any { it[0] == "restart" }, "restarting a unit that runs no daemon: ${ctl.calls}")
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `a restart systemd refuses is reported with the unit's status and journal`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(OLD)
        val ctl = FakeSystemctl(active = true, restartOk = false)
        try {
            val restarted = command(tmp, daemon.port, ctl).restart(NEW, waitForCompactions = false)
            assertFalse(restarted, "a refused restart is a failed restart")
            val said = lines.joinToString("\n")
            assertTrue("journalctl --user -u $UNIT" in said && "systemctl --user status $UNIT" in said, said)
            assertEquals(0, daemon.shutdowns.get(), "a refused restart must not stop the daemon here either")
        } finally {
            daemon.close()
        }
    }
}
