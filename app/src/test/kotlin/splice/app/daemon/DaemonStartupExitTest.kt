// A process exit that arrives while the daemon is still starting must still run the ordered stop, and the stop has to
// reach every resource startup holds. The shutdown hook's signal ends the startup wait, so main's stop always runs under
// the halt watchdog, and the control server is owned from the moment startup builds it, so a stop between the bind and the
// publication cannot leave a live listener. The lock is released last: the child resumes its held startup only once the
// listener has closed, and reports whether the lock was still held then.
//
// b9bf26450 awaited startup first: the hook timed out after 57 s and main never reached its stop. ff0f6442f ended the wait but
// published the server only after startup returned, so a stop held at the bind released the lock under a live listener.
// Driven in a real JVM, because the hook, System.exit and the held startup thread exist only there.
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
    fun `an exit while startup is held after the daemon is up still runs the ordered stop`(@TempDir tmp: Path) {
        val run = runChild(tmp, HOLD_UP)
        assertOrdered(run)
    }

    @Test
    fun `an exit while startup is held between the control bind and its publication leaves no live listener`(
        @TempDir tmp: Path,
    ) {
        val run = runChild(tmp, HOLD_BOUND)
        assertOrdered(run)
        assertTrue(run.output.contains(UP_PORT_LIVE + "false")) {
            "startup resumed after the stop and found the control listener live:\n${run.output}"
        }
    }

    private data class Run(val port: Int, val exited: Boolean, val code: Int?, val output: String)

    private fun assertOrdered(run: Run) {
        assertTrue(run.output.contains(STARTUP_HELD)) { "the child never reached the held startup:\n${run.output}" }
        assertTrue(run.exited) {
            "the JVM was still alive ${EXIT_BOUND_S}s after System.exit during startup, so the ordered stop never " +
                "closed the control listener the startup held:\n${run.output}"
        }
        assertTrue(run.output.contains(PORT_CLOSED_LOCK_HELD)) {
            "the control listener was not closed, or the daemon lock was already released when it closed:\n${run.output}"
        }
        assertEquals(0, run.code) { run.output }
        assertFalse(run.output.contains("halting")) { "the halt watchdog had to cut the stop:\n${run.output}" }
        assertTrue(portIsFree(run.port)) { "the control port is still bound after the exit" }
    }

    private fun runChild(tmp: Path, holdAt: String): Run {
        val port = TestPorts.reserve()
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "splice.app.daemon.HeldStartupDaemonKt",
            tmp.resolve("state").toString(),
            port.toString(),
            holdAt,
        ).redirectErrorStream(true).also { it.environment()["HOME"] = tmp.toString() }.start()
        val output = StringBuffer()
        val reader = Thread { child.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') } }
        reader.isDaemon = true
        reader.start()
        try {
            val exited = child.waitFor(EXIT_BOUND_S, TimeUnit.SECONDS)
            reader.join(TimeUnit.SECONDS.toMillis(2))
            return Run(port, exited, if (exited) child.exitValue() else null, output.toString())
        } finally {
            child.destroyForcibly()
        }
    }

    private fun portIsFree(port: Int): Boolean = runCatching { ServerSocket(port).close() }.isSuccess
}
