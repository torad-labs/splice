// NEW: V4-295 — POST /api/alerts/test says what failed without the saved URL's path, where a webhook's
// secret lives. Ktor's timeouts put the whole URL in their message, so the route answered it verbatim.
//
// The webhooks are real sockets: one that completes the handshake in the kernel's backlog and never
// answers (a timeout), and a port bound then closed (a refused connection).
package splice.usage.alerts

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path

private const val SECRET = "s3cr3t-T0KEN"
private const val TIMEOUT_MS = 300L

class AlertRoutesTest {

    @Test
    fun `a webhook that never answers is named a timeout without its path - V4-295`(@TempDir tmp: Path) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            val error = testSend(tmp, "http://127.0.0.1:${silent.localPort}/hook/$SECRET")

            assertFalse(SECRET in error || "/hook" in error, "no part of the path is answered: $error")
            assertTrue("webhook test failed: " in error && "Timeout" in error && "127.0.0.1" in error, error)
        }
    }

    @Test
    fun `a webhook that refuses the connection is named without its path - V4-295`(@TempDir tmp: Path) {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        val error = testSend(tmp, "http://127.0.0.1:$port/hook/$SECRET")

        assertFalse(SECRET in error || "/hook" in error, "no part of the path is answered: $error")
        assertTrue("webhook test failed: " in error && "Connect" in error && "127.0.0.1" in error, error)
    }

    /** The route's test send against [url] with a short timeout; the 502's error text. */
    private fun testSend(tmp: Path, url: String): String {
        val store = AlertStore(tmp.resolve("alerts.json"))
        store.replace(AlertSettings(webhookUrl = url))
        val client = HttpClient(Java) { install(HttpTimeout) { requestTimeoutMillis = TIMEOUT_MS } }
        val reply = client.use { runBlocking { AlertRoutes({ store }, it).test() } }
        assertEquals(HttpStatusCode.BadGateway, reply.status, reply.body)
        return reply.body
    }
}
