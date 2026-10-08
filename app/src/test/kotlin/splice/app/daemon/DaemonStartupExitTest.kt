// A process exit that arrives while the daemon is still starting must still run the ordered stop. The signal the shutdown
// hook sends has to end the startup wait, so main's stop always runs and the halt watchdog bounds it. b9bf26450 awaited
// startup first: the hook timed out after 57 s, main never reached its stop, and the JVM left with the port bound and no
// ordered stop. Driven in a real JVM, because the hook, System.exit and the stuck startup thread exist only there.
package splice.app.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val EXIT_BOUND_S = 25L

class DaemonStartupExitTest {

    @Test
    fun `an exit during startup runs the ordered stop and leaves well inside the hook's bound`(@TempDir tmp: Path) {
        val port = TestPorts.reserve()
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "splice.app.daemon.HeldStartupDaemonKt",
            tmp.resolve("state").toString(),
            port.toString(),
        ).redirectErrorStream(true).also { it.environment()["HOME"] = tmp.toString() }.start()
        val output = StringBuilder()
        val reader = Thread { child.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply {
            isDaemon = true
            start()
        }
        try {
            val exited = child.waitFor(EXIT_BOUND_S, TimeUnit.SECONDS)
            reader.join(TimeUnit.SECONDS.toMillis(2))
            assertTrue(output.contains(STARTUP_HELD)) { "the child never reached the held startup:\n$output" }
            assertTrue(exited) {
                "the JVM was still alive ${EXIT_BOUND_S}s after System.exit during startup: the ordered stop never ran " +
                    "(the hook waits 57s and main never reaches its stop):\n$output"
            }
            assertEquals(0, child.exitValue()) { output.toString() }
            assertFalse(output.contains("halting")) { "the halt watchdog had to cut the stop:\n$output" }
            assertTrue(portIsFree(port)) { "the control port is still bound after the exit" }
        } finally {
            child.destroyForcibly()
        }
    }

    private fun portIsFree(port: Int): Boolean = runCatching { ServerSocket(port).close() }.isSuccess
}
