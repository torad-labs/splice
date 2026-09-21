// V4-141: the three facts the OkHttp swap stands on, each pinned where the load test would only
// show the symptom. (1) The Dispatcher's per-host ceiling is UPSTREAM_MAX_REQUESTS, not OkHttp's
// default 5 — at 5 the daemon serialises every concurrent turn against one provider behind five
// sockets, and the 1000-stream load test at RAMP_CONCURRENCY 96 would hang, not fail by name.
// (2) The threads under it — OkHttp's calls and ktor's blocking body readers alike — are virtual,
// because a held stream parks one for its whole life; on Dispatchers.IO the first swap deadlocked
// the load test at exactly the ramp width (96 held, 0 live). (3) A socket the factory hands out is
// armed — SO_KEEPALIVE, TCP_NODELAY and the three keepalive timings — BEFORE it connects, which is
// the whole reason the engine changed. The `ss -o state established` observation on the live daemon
// is the manual acceptance the ledger row keeps beside this.
package splice.upstream.transport

import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class UpstreamTransportOkHttpTest {

    @Test
    fun `dispatcher ceiling is the daemon's, not OkHttp's five per host`() {
        val dispatcher = UpstreamTransport().upstreamDispatcher()
        assertEquals(UPSTREAM_MAX_REQUESTS, dispatcher.maxRequestsPerHost)
        assertEquals(UPSTREAM_MAX_REQUESTS, dispatcher.maxRequests)
        assertTrue(UPSTREAM_MAX_REQUESTS >= 1000, "the 1000-stream load test must fit under the per-host ceiling")
    }

    @Test
    fun `dispatcher runs calls on virtual threads`() {
        val executor = UpstreamTransport().upstreamDispatcher().executorService
        val virtual = CompletableFuture<Boolean>()
        executor.execute { virtual.complete(Thread.currentThread().isVirtual) }
        assertTrue(virtual.get(5, TimeUnit.SECONDS), "OkHttp's executor must be virtual threads")
        executor.shutdown()
    }

    @Test
    fun `the engine reads response bodies on virtual threads, not Dispatchers IO`() {
        val client = UpstreamTransport().defaultClient(1_000, 1_000)
        val virtual = runBlocking { withContext(client.engine.dispatcher) { Thread.currentThread().isVirtual } }
        client.close()
        assertTrue(virtual, "ktor's OkHttp engine blocks a thread per held stream; it must be a virtual one")
    }

    @Test
    fun `a socket from the factory is armed before it connects`() {
        val socket = KeepaliveSocketFactory().createSocket()
        socket.use {
            assertTrue(!it.isConnected, "OkHttp connects the socket itself; the factory must not")
            assertArmed(it)
        }
    }

    @Test
    fun `arming survives the connect`() {
        ServerSocket(0).use { server ->
            KeepaliveSocketFactory().createSocket("127.0.0.1", server.localPort).use { client ->
                server.accept().use {
                    assertTrue(client.isConnected)
                    assertArmed(client)
                }
            }
        }
    }

    private fun assertArmed(socket: Socket) {
        assertTrue(socket.keepAlive, "SO_KEEPALIVE")
        assertTrue(socket.tcpNoDelay, "TCP_NODELAY")
        assertEquals(KEEPALIVE_IDLE_S, socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE))
        assertEquals(KEEPALIVE_INTERVAL_S, socket.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL))
        assertEquals(KEEPALIVE_PROBES, socket.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT))
    }
}
