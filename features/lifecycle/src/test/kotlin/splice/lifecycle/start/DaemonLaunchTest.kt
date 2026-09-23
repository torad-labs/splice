// NEW (G25): idle heap uncommit — DEFAULT_JVM_OPTS must carry -XX:G1PeriodicGCInterval=60000
// alongside the pre-existing G10 flags (-Xmx2048m, -XX:+UseStringDeduplication), since both
// cold-start paths (DaemonLaunch's argv and app/src/main/dist/bin/splice-launch) are meant to agree.
// Moved from app's AdminSupportTest with the cold start (LAYOUT-01); the selfJar arms stayed there.
package splice.lifecycle.start

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.daemonclient.DaemonHealth
import splice.daemonclient.DaemonSettings
import java.net.ServerSocket
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

    @Test
    fun `DEFAULT_JVM_OPTS carries the G1 periodic GC interval flag`() {
        assertTrue(DEFAULT_JVM_OPTS.contains("-XX:G1PeriodicGCInterval=60000"))
    }

    @Test
    fun `DEFAULT_JVM_OPTS keeps the pre-existing heap cap and string-dedup flags`() {
        assertTrue(DEFAULT_JVM_OPTS.contains("-Xmx2048m"))
        assertTrue(DEFAULT_JVM_OPTS.contains("-XX:+UseStringDeduplication"))
        assertTrue(DEFAULT_JVM_OPTS.contains("-XX:G1PeriodicGCInterval=60000"))
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

    // F149 (review #94): jar and logsDir ride as argv DATA, never interpolated into the sh -c
    // string — an apostrophe in the install path ("/home/o'brien") used to break out of the
    // single-quoted literal and kill the cold start on a shell parse error.
    @Test
    fun `daemon launch passes jar and logsDir as positional argv, never inside the shell string`() {
        val jar = Path.of("/home/o'brien/splice.jar")
        val logs = Path.of("/home/o'brien/.splice/logs")
        val argv = launch().daemonLaunchArgv(jar, logs)
        val script = argv[argv.indexOf("-c") + 1]
        assertFalse(script.contains("o'brien"), "paths must not be interpolated into the shell script")
        assertTrue(argv.takeLast(2) == listOf(jar.toString(), logs.toString()), "paths ride as positional argv")
        assertTrue(script.contains("\"\$1\"") && script.contains("\"\$2\""), "the script reads them as data")
    }

    // JW-01: the spawned JVM's output lands in daemon-boot.log, never /dev/null — a boot stack
    // trace has to be tailable. /dev/null survives only as the fallback for a logs dir the
    // redirect probe cannot write. The shim's twin of this redirect is rehearsed in
    // tools/release's launcher harness ("JW-01 the boot log is written and shown").
    @Test
    fun `daemon launch redirects the JVM into the boot log, dev-null only as the unwritable fallback - JW-01`() {
        val argv = launch().daemonLaunchArgv(Path.of("/opt/splice.jar"), Path.of("/var/log/splice"))
        val script = argv[argv.indexOf("-c") + 1]
        assertTrue(script.contains("B=\"\$L/daemon-boot.log\""), "the boot log is named off the logs dir: $script")
        assertTrue(
            script.contains("daemon >>\"\$B\" 2>&1"),
            "the JVM's stdout and stderr append to the boot log: $script",
        )
        val fallback = script.indexOf("daemon >/dev/null 2>&1")
        assertTrue(fallback > script.indexOf("else"), "/dev/null is reachable only through the else branch: $script")
    }
}
