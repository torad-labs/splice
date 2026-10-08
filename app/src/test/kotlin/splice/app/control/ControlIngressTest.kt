// NEW: the real control listener spends the same JVM ledger as inference heads.
package splice.app.control

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.upstream.memory.JvmHeap
import java.net.Socket
import java.nio.file.Path

class ControlIngressTest {
    @Test
    fun `stopping the control listener preserves another heap owner's charge`(@TempDir tmp: Path) {
        val other = checkNotNull(JvmHeap.budget.reserve(splice.core.memory.HeapWeights.CONNECTION_BYTES))
        try {
            `control routes refuse shared pressure before rendering and recover after release`(tmp)
            assertTrue(JvmHeap.budget.available.value <= JvmHeap.budget.limitBytes - other.bytes)
        } finally {
            other.close()
        }
    }

    @Test
    fun `control routes refuse shared pressure before rendering and recover after release`(@TempDir tmp: Path) {
        val before = JvmHeap.budget.available.value
        val paths = StatePaths(baseOverride = tmp)
        val server = ControlServer(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = MgmtKey(paths, log = {}),
            log = {},
        )
        runBlocking { server.start() }
        try {
            val hold = checkNotNull(JvmHeap.budget.reserve(JvmHeap.budget.available.value))
            try {
                val reply = request(server.listeningPort)
                assertTrue(reply.startsWith("HTTP/1.1 529"), reply)
                assertTrue(reply.contains("overloaded_error"), reply)
                assertFalse(reply.contains("readyHeads"), "the refused request reached the /health handler: $reply")
            } finally {
                hold.close()
            }
            val reply = request(server.listeningPort)
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply)
            assertTrue(reply.contains("readyHeads"), reply)
        } finally {
            server.stop()
        }
        runBlocking { withTimeout(5000) { JvmHeap.budget.available.first { it >= before } } }
        assertTrue(JvmHeap.budget.available.value >= before, "the listener returns its own starting capacity")
    }

    private fun request(port: Int): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write(
            "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
