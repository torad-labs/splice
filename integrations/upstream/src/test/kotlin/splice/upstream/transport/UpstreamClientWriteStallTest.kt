// NEW: V4-272 — a request the upstream stops taking is cut at the head's firstByteTimeout, named,
// and retried on a fresh connection; a request fully written whose headers are late is not cut.
// Live (film home, 2026-09-26, train 28's jar): a turn sat 264 s in the gate's connect phase with
// 195,768 bytes in its upstream socket's send queue after a wifi stall, and daemon.log said nothing
// until the client was interrupted. Nothing bounded the request's write short of the whole turn cap:
// the idle watchdog is armed only after a 2xx, and OkHttp's write timeout was the cap itself.
// Real sockets, because the stall is the kernel's: a peer that stops reading fills its small receive
// window and then the client's send buffer (4 MB at most on Linux, tcp_wmem), and the next write
// blocks. The 16 MB body is larger than both.
package splice.upstream.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.RetryNotice
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The head's firstByteTimeout in this rig: how long a write may take none of the request. */
private const val WRITE_TIMEOUT_MS = 1_000L

/** The whole-turn cap, which bounded the stalled write before this row. */
private const val TOTAL_MS = 12_000L

/** A prefill that runs past the write timeout before the upstream sends its headers. */
private const val PREFILL_MS = 3 * WRITE_TIMEOUT_MS

private const val SMALL_WINDOW = 4096
private val BIG_BODY = "{\"pad\":\"" + "x".repeat(16 shl 20) + "\"}"

class UpstreamClientWriteStallTest {

    private val upstreams = mutableListOf<Upstream>()

    @AfterEach
    fun close() {
        upstreams.forEach(Upstream::close)
    }

    @Test
    fun `a request the upstream never reads is cut at the write timeout, named, and retried on a fresh connection`() {
        assertCutAndRetried(Upstream(readBytes = 0).also(upstreams::add))
    }

    @Test
    fun `a request the upstream stops reading partway is cut the same way`() {
        assertCutAndRetried(Upstream(readBytes = 256 * 1024).also(upstreams::add))
    }

    @Test
    fun `a request fully written whose headers come after the write timeout is not cut`() {
        val upstream = Upstream(readBytes = null).also(upstreams::add)
        val retries = CopyOnWriteArrayList<String>()
        val started = System.nanoTime()

        val answer = runBlocking { client().posted(context(upstream, retries), BIG_BODY) { "ok" } }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("ok", answer)
        assertTrue(elapsedMs >= PREFILL_MS, "the headers came after the write timeout: ${elapsedMs}ms")
        assertEquals(1, upstream.accepted.get(), "one connection: nothing was cut")
        assertTrue(retries.isEmpty(), "no retry: $retries")
    }

    private fun assertCutAndRetried(upstream: Upstream) {
        val retries = CopyOnWriteArrayList<String>()
        val started = System.nanoTime()

        val thrown = runCatching {
            runBlocking { client().posted(context(upstream, retries), BIG_BODY) { "ok" } }
        }.exceptionOrNull()

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(
            elapsedMs < TOTAL_MS / 2,
            "two attempts, each cut at the ${WRITE_TIMEOUT_MS}ms write timeout, not at the ${TOTAL_MS}ms cap: " +
                "${elapsedMs}ms, $thrown",
        )
        assertTrue(
            generateSequence(thrown) { it.cause }.any { it is RequestWriteStalled },
            "the failure is named a stalled write: $thrown",
        )
        assertEquals(2, upstream.accepted.get(), "the retry went out on a fresh connection")
        val line = retries.single()
        assertTrue(line.startsWith("transport "), "a request the upstream never got whole is no duplicate: $line")
        assertTrue(line.contains("took none of the request"), "the retry line names the stall: $line")
    }

    private fun client() = UpstreamClient(
        totalTimeoutMs = TOTAL_MS,
        maxRetries = 2,
        client = UpstreamTransport().defaultClient(TOTAL_MS, requestWriteTimeoutMs = WRITE_TIMEOUT_MS),
        waiter = RecordingWaiter(),
    )

    private fun context(upstream: Upstream, retries: MutableList<String>) = PostContext(
        url = "http://127.0.0.1:${upstream.port}/v1/messages",
        auth = fakeAuth,
        extraHeaders = { emptyMap() },
        onRetry = RetryNotice { retries += it },
    )
}

/**
 * An upstream on loopback with a small receive window. It reads [readBytes] of each request and then
 * stops reading with the connection left open; or, when [readBytes] is null, reads the whole request,
 * takes [PREFILL_MS] as a model's prefill would, and answers 200.
 */
private class Upstream(private val readBytes: Int?) : AutoCloseable {
    private val server = ServerSocket().apply {
        receiveBufferSize = SMALL_WINDOW
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    }
    private val sockets = CopyOnWriteArrayList<Socket>()
    val accepted = AtomicInteger()
    val port: Int get() = server.localPort

    init {
        val _ = thread(isDaemon = true, name = "v4272-upstream") {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                accepted.incrementAndGet()
                sockets += socket
                val _ = thread(isDaemon = true, name = "v4272-upstream-conn") { val _ = runCatching { serve(socket) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        if (readBytes != null) {
            input.readNBytes(readBytes)
            return
        }
        readRequest(input)
        runBlocking { delay(PREFILL_MS) }
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok".toByteArray())
            flush()
        }
    }

    private fun readRequest(input: InputStream) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.append(b.toChar())
        }
        val length = head.lines().firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toInt() ?: 0
        input.readNBytes(length)
    }

    override fun close() {
        server.close()
        sockets.forEach { val _ = runCatching { it.close() } }
    }
}
