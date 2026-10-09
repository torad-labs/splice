package splice.lifecycle.restart

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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DetachedSuccessorRestartTest {
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
            SuccessorInstall(home, null, home.resolve("state"), TestPorts.reserve()),
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
    fun `successor JVM flags keep the browser guard and home without multiplying on restart`() {
        val home = Path.of("/synthetic/home")
        val successor = DetachedDaemonSuccessor(
            null,
            SuccessorInstall(home, null, home.resolve("state"), 31999),
            home.resolve("logs"),
            LogSink {},
            123L,
        )
        val quotedHome = "/synthetic/a'b\"c space"
        val flags = successor.inheritedJvmOptions("-Xmx512m", quotedHome, "1")
        assertEquals(
            "-Xmx512m -Duser.home=\"/synthetic/a'b\"'\"'\"c space\" " +
                "-Dsplice.noSystemBrowser=\"1\"",
            flags,
        )
        assertEquals(flags, successor.inheritedJvmOptions(flags, quotedHome, "1"))
        val java = ProcessBuilder("java", "-XshowSettings:properties", "-version").apply {
            environment()["JAVA_TOOL_OPTIONS"] = flags
        }.start()
        val finished = java.waitFor(20, TimeUnit.SECONDS)
        if (!finished) java.destroy()
        assertTrue(finished, "JVM did not finish parsing the inherited options")
        val properties = java.errorStream.bufferedReader().use { it.readText() }
        assertEquals(0, java.exitValue(), properties)
        assertTrue(properties.contains("user.home = $quotedHome"), properties)
        assertTrue(properties.contains("splice.noSystemBrowser = 1"), properties)
    }
}
