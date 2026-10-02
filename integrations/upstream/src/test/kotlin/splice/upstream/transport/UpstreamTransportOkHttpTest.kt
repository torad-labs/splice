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

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// Longer than any per-read bound a first-byte tier would set in this test, far under its total cap.
private const val SILENT_GAP_MS = 1_500
private const val SSE_HEAD = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
private val BODY = "{}".toByteArray()

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
        val client = UpstreamTransport().defaultClient(1_000)
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

    // V4-125: under OkHttp, HttpTimeout's socketTimeoutMillis is the PER-READ timeout. Bound to the
    // first-byte tier (90s in production) it tore a silent-but-alive peer, the case the watchdog's
    // probe-then-hold exists to keep; the only wall short of the peer dying is the total cap. The peer
    // goes silent after its first frame and speaks again once the client has stayed connected for the
    // whole gap, or reports that it hung up.
    @Test
    fun `a peer that goes silent but stays connected is read to the end`() {
        ServerSocket(0).use { server ->
            val served = CompletableFuture.supplyAsync { serveWithSilentGap(server) }
            val client = UpstreamTransport().defaultClient(totalTimeoutMs = 10_000)
            val body = try {
                runBlocking { client.get("http://127.0.0.1:${server.localPort}/").bodyAsText() }
            } finally {
                client.close()
            }
            assertEquals("stayed connected", served.get(10, TimeUnit.SECONDS))
            assertTrue("data: last" in body, body)
        }
    }

    /** Oct 2: with no Accept-Encoding of splice's own, OkHttp asked every upstream for gzip on the wire and
     *  inflated the answer out of the trace's sight; a compressed event stream reaches the reader in blocks,
     *  not one event at a time. The head as the socket reads it is the only place OkHttp's own header shows. */
    @Test
    fun `an upstream post asks for an uncompressed answer on the wire`() {
        assertEquals("identity", wireAcceptEncoding(provider = emptyMap()))
    }

    @Test
    fun `a provider's own Accept-Encoding still replaces the uncompressed default`() {
        assertEquals("br", wireAcceptEncoding(provider = mapOf("accept-encoding" to "br")))
    }

    /** The Accept-Encoding the socket read on one prepared upstream POST, null when there was none. */
    private fun wireAcceptEncoding(provider: Map<String, String>): String? = ServerSocket(0).use { server ->
        val head = CompletableFuture.supplyAsync {
            server.accept().use { peer ->
                val text = readRequestHead(peer.getInputStream())
                peer.getOutputStream().write((SSE_HEAD + chunk("data: one\n\n") + "0\r\n\r\n").toByteArray())
                peer.getOutputStream().flush()
                text
            }
        }
        val client = UpstreamTransport().defaultClient(totalTimeoutMs = 10_000)
        try {
            runBlocking {
                UpstreamRequest(client, zstdRequestBody = false)
                    .prepare("http://127.0.0.1:${server.localPort}/", Credentials.ClientForwarded, { provider }, BODY)
                    .execute { it.bodyAsText() }
            }
        } finally {
            client.close()
        }
        head.get(10, TimeUnit.SECONDS).lineSequence()
            .map { it.split(":", limit = 2) }
            .firstOrNull { it.size == 2 && it[0].trim().equals("accept-encoding", ignoreCase = true) }
            ?.get(1)?.trim()
    }

    private fun serveWithSilentGap(server: ServerSocket): String = server.accept().use { peer ->
        val input = peer.getInputStream()
        val output = peer.getOutputStream()
        readRequestHead(input)
        output.write((SSE_HEAD + chunk("data: first\n\n")).toByteArray())
        output.flush()
        peer.soTimeout = SILENT_GAP_MS
        val hungUp = try {
            input.read() == -1
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
        if (hungUp) return@use "hung up"
        output.write((chunk("data: last\n\n") + "0\r\n\r\n").toByteArray())
        output.flush()
        "stayed connected"
    }

    private fun readRequestHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next == -1) break
            head.append(next.toChar())
        }
        return head.toString()
    }

    private fun chunk(text: String) = "${text.length.toString(16)}\r\n$text\r\n"

    private fun assertArmed(socket: Socket) {
        assertTrue(socket.keepAlive, "SO_KEEPALIVE")
        assertTrue(socket.tcpNoDelay, "TCP_NODELAY")
        assertEquals(KEEPALIVE_IDLE_S, socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE))
        assertEquals(KEEPALIVE_INTERVAL_S, socket.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL))
        assertEquals(KEEPALIVE_PROBES, socket.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT))
    }
}
