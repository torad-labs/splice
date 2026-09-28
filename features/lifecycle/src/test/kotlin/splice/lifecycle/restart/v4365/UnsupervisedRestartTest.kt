package splice.lifecycle.restart.v4365

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.lifecycle.restart.CompactionsInFlight
import splice.lifecycle.restart.DaemonRestarts
import splice.lifecycle.restart.DaemonSuccessor
import splice.lifecycle.restart.DaemonSupervised
import splice.lifecycle.restart.DetachedDaemonSuccessor
import splice.lifecycle.restart.RestartAfterCompactions
import splice.lifecycle.restart.RestartCommand
import splice.lifecycle.restart.RestartPhase
import splice.lifecycle.restart.RestartTaken
import splice.lifecycle.restart.ShutdownDaemon
import java.nio.file.Files
import java.nio.file.Path

class UnsupervisedRestartTest {
    private fun restarts(): DaemonRestarts = DaemonRestarts(
        RestartAfterCompactions(
            CompactionsInFlight { emptyList() },
            CoroutineScope(Dispatchers.Default),
            LogSink {},
        ),
    )

    @Test
    fun `an unsupervised restart is taken on rather than refused`() {
        var drains = 0
        var launches = 0
        val restarts = restarts()
        restarts.wireSuccessor(
            DaemonSuccessor {
                launches++
                true
            },
        )
        val shutdown = ShutdownDaemon { drains++ }
        val taken = restarts.take(now = false, shutdown, DaemonSupervised { false })
        assertInstanceOf(RestartTaken.Accepted::class.java, taken)
        assertEquals(0, drains, "the response must precede the drain")
        assertEquals(1, launches)
        assertTrue(restarts.phase() != RestartPhase.Idle)
        assertInstanceOf(
            RestartTaken.Accepted::class.java,
            restarts.take(now = true, shutdown, DaemonSupervised { false }),
        )
        assertEquals(1, launches, "a second request must not arm another child")
    }

    @Test
    fun `supervised restarts never arm a competing successor and failed launches never drain`() {
        val restarts = restarts()
        var launches = 0
        restarts.wireSuccessor(
            DaemonSuccessor {
                launches++
                false
            },
        )
        val shutdown = ShutdownDaemon { error("a refused restart must not drain") }
        assertInstanceOf(RestartTaken.Refused::class.java, restarts.take(false, shutdown, DaemonSupervised { false }))
        assertEquals(1, launches)
        assertInstanceOf(RestartTaken.Accepted::class.java, restarts.take(false, shutdown, DaemonSupervised { true }))
        assertEquals(1, launches, "systemd is the only supervisor of its daemon")
    }

    @Test
    fun `the detached launcher can actually start its shell`(@TempDir home: Path) {
        val jar = home.resolve("fake.jar")
        Files.writeString(jar, "not a jar")
        val logs = home.resolve("logs")
        val failures = mutableListOf<String>()
        val successor = DetachedDaemonSuccessor(
            jar,
            home,
            null,
            home.resolve("state"),
            TestPorts.reserve(),
            logs,
            LogSink { failures += it },
            Long.MAX_VALUE,
        )
        assertTrue(successor.start(), failures.toString())
    }

    @Test
    fun `a malformed config without a running daemon says it cannot start`(@TempDir home: Path) {
        val config = home.resolve("splice.toml")
        Files.writeString(config, "[heads\n")
        val lines = mutableListOf<String>()
        val output = TerminalOutput { lines += it }
        val port = TestPorts.reserve()
        val env = EnvReader { key ->
            when (key) {
                "SPLICE_CONFIG" -> config.toString()
                "SPLICE_CONTROL_PORT" -> port.toString()
                else -> null
            }
        }
        val command = RestartCommand(output, output, env, RunningJar { null })
        assertEquals(false, command.restart(waitForCompactions = false))
        assertTrue(lines.any { it.contains("cannot start the daemon until $config is fixed") }, lines.toString())
        assertTrue(lines.none { it.contains("falling back to the running daemon") }, lines.toString())
    }

    @Test
    fun `the detached CLI inherits the boot home config state and control port`() {
        val home = Path.of("/synthetic/home")
        val jar = Path.of("/synthetic/install/splice.jar")
        val config = home.resolve("custom.toml")
        val successor = DetachedDaemonSuccessor(
            jar,
            home,
            config,
            home.resolve("state"),
            31999,
            home.resolve("logs"),
            LogSink {},
            123L,
        )
        assertEquals(listOf("sh", "-c"), successor.command(jar).take(2))
        assertEquals(listOf("sh", "123", jar.toString()), successor.command(jar).takeLast(3))
        assertTrue(successor.command(jar)[2].contains("restart --now"))
        assertEquals(
            mapOf(
                "HOME" to home.toString(),
                "SPLICE_CONFIG" to config.toString(),
                "SPLICE_STATE_DIR" to home.resolve("state").toString(),
                "SPLICE_CONTROL_PORT" to "31999",
                "SPLICE_JAR" to jar.toString(),
            ),
            successor.selectors(jar),
        )
    }
}
