// NEW: custom-provider (cp-ready) — a bound port may hold a daemon that is up but has not yet answered its first
// /health, indistinguishable from one stuck mid-shutdown until it either answers or frees the port. waitForBoundDaemon
// is the one place that tells those apart; HealthProbeTest pins the probe cases it reads.
package splice.daemonclient

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DaemonHealthTest {

    private val servers = mutableListOf<HttpServer>()
    private val health = DaemonHealth()

    @AfterEach
    fun stop() {
        servers.forEach { it.stop(0) }
    }

    /** A listener whose /health answers [body] once [answer] (default: always) says so; otherwise it closes without
     *  ever sending a response, the way a daemon not yet routing /health, or one mid-shutdown, looks to a probe. */
    private fun listener(body: String = """{"version":"0.4.0"}""", answer: () -> Boolean = { true }): Int {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/health") { exchange ->
            if (answer()) {
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.close()
            }
        }
        http.start()
        servers += http
        return http.address.port
    }

    @Test
    fun `a listener already answering the expected version is Up at once`() {
        val port = listener()
        assertEquals(BoundDaemon.Up, health.waitForBoundDaemon(port, "0.4.0", polls = 3, intervalMs = 5))
    }

    @Test
    fun `a listener answering another version is OtherVersion, never waited out further`() {
        val port = listener(body = """{"version":"9.9.9"}""")
        assertEquals(BoundDaemon.OtherVersion, health.waitForBoundDaemon(port, "0.4.0", polls = 3, intervalMs = 5))
    }

    @Test
    fun `a port nothing holds is Free`() {
        val freed = ServerSocket(0).also { it.close() }
        assertEquals(BoundDaemon.Free, health.waitForBoundDaemon(freed.localPort, "0.4.0", polls = 3, intervalMs = 5))
    }

    @Test
    fun `a listener that never answers within the budget is Starting`() {
        val port = listener(answer = { false })
        assertEquals(BoundDaemon.Starting, health.waitForBoundDaemon(port, "0.4.0", polls = 2, intervalMs = 5))
    }

    // custom-provider (cp-ready): the scenario this item fixes — a daemon that is up but slow on its first /health
    // answer must be found once it does answer, not refused as "still shutting down" the moment the budget starts.
    @Test
    fun `a listener that starts answering partway through the wait is found, short of the budget`() {
        val attempts = AtomicInteger(0)
        val port = listener(answer = { attempts.incrementAndGet() >= 3 })
        assertEquals(BoundDaemon.Up, health.waitForBoundDaemon(port, "0.4.0", polls = 10, intervalMs = 5))
    }
}
