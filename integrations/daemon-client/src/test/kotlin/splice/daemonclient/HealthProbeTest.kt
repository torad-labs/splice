// NEW: V4-230 — the /health probe tells its cases apart. It read a refused connection, a timeout and a
// non-2xx alike as null, so doctor called a daemon too busy to answer in 400ms stopped. The refused
// case, which needs a port nothing holds, is pinned where doctor reads it (DoctorSelfProbeTest).
package splice.daemonclient

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

class HealthProbeTest {

    private val servers = mutableListOf<HttpServer>()

    /** Holds every held /health until the test is done with the probe. */
    private val release = CountDownLatch(1)

    @AfterEach
    fun stop() {
        release.countDown()
        servers.forEach { it.stop(0) }
    }

    /** A listener whose /health answers [status] with [body]; [held] makes it answer only after the
     *  probe's window closed. */
    private fun listener(status: Int = 200, body: String = """{"version":"0.4.0"}""", held: Boolean = false): Int {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/health") { exchange ->
            if (held) release.await()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        servers += http
        return http.address.port
    }

    @Test
    fun `a splice health answer is up, with what it said`() {
        val probe = assertInstanceOf(DaemonProbe.HealthProbe.Up::class.java, DaemonProbe.healthProbe(listener()))
        assertEquals("0.4.0", probe.view.version)
    }

    @Test
    fun `a listener that holds health past the window is slow, never stopped`() {
        assertEquals(DaemonProbe.HealthProbe.Slow(400), DaemonProbe.healthProbe(listener(held = true)))
    }

    @Test
    fun `a listener that answers but not as splice is odd, and says how`() {
        assertEquals(
            DaemonProbe.HealthProbe.Odd("it answered HTTP 503"),
            DaemonProbe.healthProbe(listener(status = 503)),
        )
        assertEquals(
            DaemonProbe.HealthProbe.Odd("its answer is not splice's /health"),
            DaemonProbe.healthProbe(listener(body = "<html>not json</html>")),
        )
    }

    @Test
    fun `health view keeps its contract, a view when up and null otherwise`() {
        assertEquals("0.4.0", DaemonProbe.healthView(listener())?.version)
        assertEquals(null, DaemonProbe.healthView(listener(held = true)))
    }
}
