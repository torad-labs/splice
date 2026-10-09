// What the OkHttp transport must do on a real socket: read a silent-but-connected peer to the end,
// let a progressing response outlive its per-read timeout, and never ask upstream for an encoding
// splice does not decode.
package splice.upstream.transport

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// Longer than any per-read bound a first-byte tier would set in this test, far under its total cap.
private const val SILENT_GAP_MS = 1_500
private const val SSE_HEAD = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
private val BODY = "{}".toByteArray()

class UpstreamTransportOkHttpTest {

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

    @Test
    fun `a continuously progressing response can outlive its per-read timeout`() {
        ServerSocket(0).use { server ->
            val served = CompletableFuture.supplyAsync {
                server.accept().use { peer ->
                    val input = peer.getInputStream()
                    readRequestHead(input)
                    val output = peer.getOutputStream()
                    output.write(SSE_HEAD.toByteArray())
                    peer.soTimeout = 250
                    repeat(6) {
                        output.write(chunk("data: synthetic-$it\n\n").toByteArray())
                        output.flush()
                        try {
                            check(input.read() != -1) { "the client cut a progressing response" }
                        } catch (_: SocketTimeoutException) {
                            // Poll client disconnect with a socket deadline while generation continues.
                        }
                    }
                    output.write("0\r\n\r\n".toByteArray())
                    output.flush()
                }
            }
            val client = UpstreamTransport().defaultClient(totalTimeoutMs = 1_000)
            try {
                val body = runBlocking { client.get("http://127.0.0.1:${server.localPort}/").bodyAsText() }
                assertTrue("data: synthetic-5" in body, "all progress must survive the old request deadline")
                served.get(5, TimeUnit.SECONDS)
            } finally {
                client.close()
            }
        }
    }

    /** Oct 2: with no Accept-Encoding of splice's own, OkHttp asked every upstream for gzip on the wire and
     *  inflated the answer out of the trace's sight; a compressed event stream reaches the reader in blocks,
     *  not one event at a time. The head as the socket reads it is the only place OkHttp's own header shows. */
    @Test
    fun `an upstream post asks for an uncompressed answer on the wire`() {
        assertEquals("identity", wireAcceptEncoding(provider = emptyMap()))
    }

    /** splice decodes no content encoding, and OkHttp inflates only what it asked for itself: a configured
     *  gzip would reach the event parser as raw gzip bytes. */
    @Test
    fun `a configured Accept-Encoding cannot ask for an encoding splice does not decode`() {
        assertEquals("identity", wireAcceptEncoding(provider = mapOf("accept-encoding" to "gzip")))
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
                    .prepare(
                        "http://127.0.0.1:${server.localPort}/",
                        AttemptWire(Credentials.ClientForwarded, provider, BODY),
                    )
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
}
