// NEW: the real control listener spends the same JVM ledger as inference heads.
package splice.app.control

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.concurrent.atomic.AtomicInteger

class ControlIngressTest {
    @Test
    fun `control routes refuse shared pressure before rendering and recover after release`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp)
        val renders = AtomicInteger()
        val server = ControlServer(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = MgmtKey(paths, log = {}),
            dashboardHtml = {
                renders.incrementAndGet()
                "<html>dashboard</html>"
            },
            log = {},
        )
        runBlocking { server.start() }
        try {
            val hold = checkNotNull(JvmHeap.budget.reserve(JvmHeap.budget.available.value))
            try {
                val reply = request(server.listeningPort)
                assertTrue(reply.startsWith("HTTP/1.1 529"), reply)
                assertTrue(reply.contains("overloaded_error"), reply)
                assertFalse(reply.contains("dashboard"), reply)
                assertEquals(0, renders.get())
            } finally {
                hold.close()
            }
            val reply = request(server.listeningPort)
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply)
            assertTrue(reply.contains("dashboard"), reply)
            assertEquals(1, renders.get())
        } finally {
            server.stop()
        }
        assertEquals(JvmHeap.budget.limitBytes, JvmHeap.budget.available.value)
    }

    private fun request(port: Int): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write(
            "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
