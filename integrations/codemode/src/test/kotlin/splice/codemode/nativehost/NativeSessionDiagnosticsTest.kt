package splice.codemode.nativehost

import io.grpc.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSink
import java.io.IOException

class NativeSessionDiagnosticsTest {
    @Test
    fun `a dead host report retains its pid exit and typed cause without private bytes`() {
        val lines = mutableListOf<String>()
        val diagnostics = NativeSessionDiagnostics(LogSink(lines::add))
        val process = ProcessBuilder("sh", "-c", "exit 137").start()
        assertEquals(137, process.waitFor())
        val error = Status.UNAVAILABLE.withDescription("PRIVATE_STATUS_BYTES")
            .withCause(IOException("PRIVATE_GUEST_BYTES")).asRuntimeException()

        diagnostics.lost("connection-id", process, error, "session-id")

        val report = lines.single()
        assertTrue(report.contains("host_pid=${process.pid()}"))
        assertTrue(report.contains("host_alive=false"))
        assertTrue(report.contains("host_exit=137"))
        assertTrue(report.contains("grpc=UNAVAILABLE"))
        assertTrue(report.contains("IOException"))
        assertTrue(report.contains("session=session-id"))
        assertFalse(report.contains("PRIVATE_"))
    }

    @Test
    fun `a cyclic failure chain produces a bounded diagnostic without reading exception messages`() {
        val lines = mutableListOf<String>()
        val diagnostics = NativeSessionDiagnostics(LogSink(lines::add))
        val process = ProcessBuilder("sh", "-c", "exit 0").start()
        process.waitFor()
        val first = IllegalStateException("PRIVATE_FIRST_BYTES")
        val second = IOException("PRIVATE_SECOND_BYTES")
        first.initCause(second)
        second.initCause(first)

        diagnostics.lost("connection-id", process, first)

        val report = lines.single()
        assertTrue(report.length < 2048)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("IOException"))
        assertFalse(report.contains("PRIVATE_"))
    }

    @Test
    fun `a diagnostic sink failure does not replace the native failure`() {
        val diagnostics = NativeSessionDiagnostics(LogSink { throw IOException("log unavailable") })
        val process = ProcessBuilder("sh", "-c", "exit 0").start()
        process.waitFor()

        diagnostics.lost("connection-id", process, IOException("native failure"))
    }
}
