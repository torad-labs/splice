// The cold start of the daemon: it will not spawn into a still-bound control port, and the raw spawn tells the
// daemon its stderr is the boot log.
package splice.lifecycle.start

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.daemonclient.DaemonHealth
import splice.daemonclient.DaemonSettings
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class DaemonLaunchTest {

    /** The raw-spawn route on every machine: this systemctl knows no unit, so `cat` answers non-zero.
     *  The arm used to reach the real one through AdminSupport, and on a box with splice.service it
     *  took the unit route — starting the operator's unit and waiting out the whole startup budget
     *  instead of testing the refusal it names. */
    private fun launch(): DaemonLaunch {
        val out = TerminalOutput(::println)
        val health = DaemonHealth()
        val settings = DaemonSettings(TerminalOutput(System.err::println))
        val noUnit = SupervisedStart(Systemctl { 1 }, EnvReader { null }, settings)
        return DaemonLaunch(out, health, DaemonSpawn(out, health, RunningJar { null }), noUnit)
    }

    // The restart-refuses-while-bound wall: ensureDaemon must NOT cold-start into a still-bound control
    // port (that new daemon would win the just-released lock, then die on the uncaught control bind,
    // leaving zero serving). It waits the bounded window instead of spawning immediately.
    @Test
    fun `ensureDaemon refuses to cold-start while the control port is still bound`() {
        val server = ServerSocket(0)
        try {
            val start = System.nanoTime()
            val started = launch().ensureDaemon(server.localPort)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertFalse(started, "spawning INTO a still-bound control port must be refused")
            assertTrue(elapsedMs >= 1_000, "the gate waits the bounded window while bound, was ${elapsedMs}ms")
        } finally {
            server.close()
        }
    }

    // The daemon printed every daemon.log line to its stderr, which this launch sends into
    // daemon-boot.log, so the boot log grew as a second, unbounded copy of daemon.log. The launch
    // says so with BOOT_LOG_FLAG (splice-launch's twin is rehearsed in tools/release's launcher
    // harness). Run for real: the java on PATH prints the argv the script gave it into the boot log.
    @Test
    fun `the raw spawn tells the daemon its stderr is the boot log`(@TempDir tmp: Path) {
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val java = Files.writeString(bin.resolve("java"), "#!/bin/sh\nprintf 'argv:%s\\n' \"\$@\"\necho argv-end\n")
        assertTrue(java.toFile().setExecutable(true), "the java stub must be executable")
        val logs = tmp.resolve("logs")
        val script = ProcessBuilder(launch().daemonLaunchArgv(tmp.resolve("splice.jar"), logs))
        script.environment().apply {
            put("PATH", "$bin:/usr/bin:/bin")
            remove("SPLICE_JVM_OPTS")
        }
        assertEquals(0, script.start().waitFor(), "the launch script itself exits 0")

        // The JVM runs in the background (nohup … &), so its argv lands after the script returns.
        val bootLog = logs.resolve("daemon-boot.log")
        val deadline = System.nanoTime() + STUB_WAIT_NANOS
        while (!Files.readString(bootLog).contains("argv-end") && System.nanoTime() < deadline) Thread.onSpinWait()
        val argv = Files.readAllLines(bootLog).filter { it.startsWith("argv:") }.map { it.removePrefix("argv:") }
        assertEquals(listOf("daemon", BOOT_LOG_FLAG), argv.dropWhile { it != "daemon" }, "the daemon's argv: $argv")
    }

    private companion object {
        const val STUB_WAIT_NANOS = 5_000_000_000L
    }
}
