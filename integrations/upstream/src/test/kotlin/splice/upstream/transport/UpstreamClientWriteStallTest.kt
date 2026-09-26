// NEW: V4-272 — a request the upstream stops taking is cut at the head's firstByteTimeout, named,
// and retried on a fresh connection; a request fully written whose headers are late is not cut.
// Live (film home, 2026-09-26, train 28's jar): a turn sat 264 s in the gate's connect phase with
// 195,768 bytes in its upstream socket's send queue after a wifi stall, and daemon.log said nothing
// until the client was interrupted. Nothing bounded the request's write short of the whole turn cap:
// the idle watchdog is armed only after a 2xx, and OkHttp's write timeout was the cap itself.
// Real sockets, because the stall is the kernel's: a peer that stops reading fills its small receive
// window and then the client's send buffer (4 MB at most on Linux, tcp_wmem), and the next write
// blocks. The 16 MB body is larger than both.
//
// V4-289 (review of V4-272): the same cut for a request that FITS in those buffers (its write returns
// and the stall is in the kernel's queue), over TLS (where OkHttp's own write timeout wedged its
// watchdog thread on the TLS record lock), onto a new connection rather than another stale pooled
// one, and never for a request the upstream keeps taking, however slowly.
package splice.upstream.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSink
import splice.upstream.RetryNotice
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** The head's firstByteTimeout in this rig: how long a write may take none of the request. */
private const val WRITE_TIMEOUT_MS = 1_000L

/** The whole-turn cap, which bounded the stalled write before this row. */
private const val TOTAL_MS = 12_000L

/** A prefill that runs past the write timeout before the upstream sends its headers. */
private const val PREFILL_MS = 3 * WRITE_TIMEOUT_MS

/** How long the warm-up requests hold their answers, so each opens its own pooled connection. */
private const val WARM_MS = 300L

/** How long the TLS stall runs before the second connection starts: past its write timeout. */
private const val HOLD_MS = 3 * WRITE_TIMEOUT_MS / 2

private const val SMALL_WINDOW = 4096
private val BIG_BODY = "{\"pad\":\"" + "x".repeat(16 shl 20) + "\"}"

/** V4-289: a request that fits in the kernel's buffers, so its write returns before the upstream has it. */
private val BUFFERED_BODY = "{\"pad\":\"" + "x".repeat(200 shl 10) + "\"}"

/** V4-289: a link slower than 64 KiB per write timeout, read [SLOW_CHUNK] bytes every [SLOW_PAUSE_MS]. */
private val SLOW_BODY = "{\"pad\":\"" + "x".repeat(192 shl 10) + "\"}"
private const val SLOW_CHUNK = 4096
private const val SLOW_PAUSE_MS = 125L

/** A fixed client send buffer, so the slow link blocks the writer instead of vanishing into autotuning. */
private const val SLOW_SEND_BUFFER = 64 * 1024

/** A turn cap short enough to wait out, for the request the watch must not guess at. */
private const val SHORT_TOTAL_MS = 4 * WRITE_TIMEOUT_MS

class UpstreamClientWriteStallTest {

    private val upstreams = mutableListOf<Upstream>()

    @AfterEach
    fun close() {
        upstreams.forEach(Upstream::close)
    }

    private fun upstream(vararg first: Serving, then: Serving, tls: Boolean = false): Upstream =
        Upstream(first.toList(), then, if (tls) LoopbackTls.server else null).also(upstreams::add)

    @Test
    fun `a request the upstream never reads is cut at the write timeout, named, and retried on a fresh connection`() {
        assertCutAndRetried(upstream(then = Serving.Reads(0)), BIG_BODY)
    }

    @Test
    fun `a request the upstream stops reading partway is cut the same way`() {
        assertCutAndRetried(upstream(then = Serving.Reads(256 * 1024)), BIG_BODY)
    }

    @Test
    fun `a request fully written whose headers come after the write timeout is not cut`() {
        val upstream = upstream(then = Serving.Answers(PREFILL_MS))
        val retries = CopyOnWriteArrayList<String>()
        val started = System.nanoTime()

        val answer = runBlocking { client().posted(context(upstream, retries), BIG_BODY) { "ok" } }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("ok", answer)
        assertTrue(elapsedMs >= PREFILL_MS, "the headers came after the write timeout: ${elapsedMs}ms")
        assertEquals(1, upstream.accepted.get(), "one connection: nothing was cut")
        assertTrue(retries.isEmpty(), "no retry: $retries")
    }

    // V4-289 (1): the write returns once the body is in the kernel, and the header wait ran on the turn cap.
    @Test
    fun `a request that fits in the send buffer and is never read is cut at the write timeout - V4-289 (1)`() {
        assertCutAndRetried(upstream(then = Serving.Reads(0)), BUFFERED_BODY)
    }

    // V4-289 (2): every hosted head is https.
    @Test
    fun `the same request over TLS is cut at the write timeout - V4-289 (2)`() {
        assertCutAndRetried(upstream(then = Serving.Reads(0), tls = true), BUFFERED_BODY, tls = true)
    }

    // V4-289 (2): OkHttp's write timeout closed the SSLSocket from okio's one watchdog thread, and the close
    // waited, with no limit, for the TLS record lock the stalled writer held; every other write timeout in
    // the process waited behind it.
    @Test
    fun `a TLS write the upstream never takes is cut, and holds up no other connection's cut - V4-289 (2)`() {
        val tlsUpstream = upstream(then = Serving.Reads(0), tls = true)
        val plainUpstream = upstream(then = Serving.Reads(0))
        val tls = client(attempts = 1, sockets = UpstreamSockets(trust = LoopbackTls.trust))
        val plain = client(attempts = 1)

        val (tlsCut, plainCut) = runBlocking(Dispatchers.IO) {
            val first = async { timedFailure { tls.posted(context(tlsUpstream, tls = true), BIG_BODY) { "ok" } } }
            delay(HOLD_MS)
            val second = async { timedFailure { plain.posted(context(plainUpstream), BIG_BODY) { "ok" } } }
            awaitAll(first, second)
        }

        assertTrue(plainCut.ms < 3 * WRITE_TIMEOUT_MS, "the plain connection is cut at its own bound: $plainCut")
        assertTrue(tlsCut.ms < 3 * WRITE_TIMEOUT_MS, "the TLS connection is cut at its bound: $tlsCut")
        assertTrue(tlsCut.stalled && plainCut.stalled, "both named a stalled write: $tlsCut / $plainCut")
    }

    // V4-289 (3): the retry took another idle connection from the pool, on the same dead path.
    @Test
    fun `the retry after a stall goes out on a new connection, never another pooled one - V4-289 (3)`() {
        val warm = Serving.Answers(WARM_MS, requests = 1)
        val upstream = upstream(warm, warm, then = Serving.Answers(0))
        val client = client()
        runBlocking(Dispatchers.IO) {
            List(2) { async { client.posted(context(upstream), "{}") { "warm" } } }.awaitAll()
        }
        assertEquals(2, upstream.accepted.get(), "two warm connections sit idle in the pool")
        val retries = CopyOnWriteArrayList<String>()

        val answer = runCatching { runBlocking { client.posted(context(upstream, retries), BIG_BODY) { "ok" } } }

        assertEquals(3, upstream.accepted.get(), "the retry went out on a new connection: $answer, $retries")
        assertEquals("ok", answer.getOrNull(), "$answer")
    }

    // V4-289 (4): OkHttp's write timeout is per 64 KiB write, so a link slower than that was cut while it flowed.
    @Test
    fun `a request the upstream keeps taking slowly is never cut - V4-289 (4)`() {
        val upstream = upstream(then = Serving.Answers(0, pauseMs = SLOW_PAUSE_MS))
        val retries = CopyOnWriteArrayList<String>()
        val client = client(sockets = UpstreamSockets(sendBufferBytes = SLOW_SEND_BUFFER))

        val answer = runCatching { runBlocking { client.posted(context(upstream, retries), SLOW_BODY) { "ok" } } }

        assertEquals("ok", answer.getOrNull(), "a slow link that flows is never cut: $answer, $retries")
        assertEquals(1, upstream.accepted.get(), "one connection: nothing was cut")
    }

    // V4-289 on macOS: there is no /proc/net/tcp, and the watch never guesses at the kernel. A write waiting
    // in it with nothing accepted is still cut at the bound, over TLS too (the cut takes no TLS lock); a
    // request that fits in the buffers waits for the turn cap, as it did before the row.
    @Test
    fun `with no send-queue table a waiting write is still cut, and a buffered one is never guessed at - V4-289`() {
        val missing = SendQueues { null }
        val tlsUpstream = upstream(then = Serving.Reads(0), tls = true)
        val plainUpstream = upstream(then = Serving.Reads(0))
        val tls = client(attempts = 1, sockets = UpstreamSockets(trust = LoopbackTls.trust, queues = missing))
        val plain = client(attempts = 1, totalMs = SHORT_TOTAL_MS, sockets = UpstreamSockets(queues = missing))

        val waiting = runBlocking { timedFailure { tls.posted(context(tlsUpstream, tls = true), BIG_BODY) { "ok" } } }
        val buffered = runBlocking { timedFailure { plain.posted(context(plainUpstream), BUFFERED_BODY) { "ok" } } }

        assertTrue(waiting.stalled && waiting.ms < 3 * WRITE_TIMEOUT_MS, "a waiting TLS write is cut: $waiting")
        assertTrue(!buffered.stalled && buffered.ms >= SHORT_TOTAL_MS, "no cut on a guess: $buffered")
    }

    private fun assertCutAndRetried(upstream: Upstream, body: String, tls: Boolean = false) {
        val retries = CopyOnWriteArrayList<String>()
        val started = System.nanoTime()
        val sockets = if (tls) UpstreamSockets(trust = LoopbackTls.trust) else UpstreamSockets()

        val thrown = runCatching {
            runBlocking { client(sockets = sockets).posted(context(upstream, retries, tls), body) { "ok" } }
        }.exceptionOrNull()

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(
            elapsedMs < TOTAL_MS / 2,
            "two attempts, each cut at the ${WRITE_TIMEOUT_MS}ms write timeout, not at the ${TOTAL_MS}ms cap: " +
                "${elapsedMs}ms, $thrown",
        )
        assertTrue(stalled(thrown), "the failure is named a stalled write: $thrown")
        assertEquals(2, upstream.accepted.get(), "the retry went out on a fresh connection")
        val line = retries.single()
        assertTrue(line.startsWith("transport "), "a request the upstream never got whole is no duplicate: $line")
        assertTrue(line.contains("the write stalled"), "the retry line names the stall: $line")
    }

    private fun stalled(thrown: Throwable?): Boolean =
        generateSequence(thrown) { it.cause }.any { it is RequestWriteStalled }

    private suspend fun timedFailure(post: suspend () -> Unit): Cut {
        val started = System.nanoTime()
        val thrown = try {
            post()
            null
        } catch (failure: java.io.IOException) {
            failure
        } catch (failure: RuntimeException) {
            failure
        }
        return Cut((System.nanoTime() - started) / 1_000_000, stalled(thrown), thrown.toString())
    }

    private data class Cut(val ms: Long, val stalled: Boolean, val thrown: String)

    private fun client(attempts: Int = 2, totalMs: Long = TOTAL_MS, sockets: UpstreamSockets = UpstreamSockets()) =
        UpstreamClient(
            totalTimeoutMs = totalMs,
            maxRetries = attempts,
            client = UpstreamTransport().client(totalMs, LogSink {}, AtomicBoolean(true), WRITE_TIMEOUT_MS, sockets),
            waiter = RecordingWaiter(),
        )

    private fun context(upstream: Upstream, retries: MutableList<String> = mutableListOf(), tls: Boolean = false) =
        PostContext(
            url = "${if (tls) "https" else "http"}://127.0.0.1:${upstream.port}/v1/messages",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            onRetry = RetryNotice { retries += it },
        )
}

/** What the upstream does on one connection. */
private sealed class Serving {
    /** Reads [bytes] of the request and then stops reading, with the connection left open. */
    data class Reads(val bytes: Int) : Serving()

    /** Reads each request ([SLOW_CHUNK] bytes every [pauseMs] when that is set), takes [prefillMs] as a
     *  model's prefill would, and answers 200; after [requests] of them it stops reading, left open. */
    data class Answers(val prefillMs: Long, val requests: Int = Int.MAX_VALUE, val pauseMs: Long = 0) : Serving()
}

/**
 * An upstream on loopback with a small receive window, plain or TLS. Its Nth connection does [first]'s
 * Nth [Serving], and every later one does [then].
 */
private class Upstream(private val first: List<Serving>, private val then: Serving, tls: SSLContext?) : AutoCloseable {
    private val server: ServerSocket = (tls?.serverSocketFactory?.createServerSocket() ?: ServerSocket()).apply {
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
                val serving = first.getOrElse(accepted.getAndIncrement()) { then }
                sockets += socket
                val _ = thread(isDaemon = true, name = "v4272-upstream-conn") {
                    val _ = runCatching { serve(socket, serving) }
                }
            }
        }
    }

    private fun serve(socket: Socket, serving: Serving) {
        (socket as? SSLSocket)?.startHandshake()
        val input = BufferedInputStream(socket.getInputStream())
        when (serving) {
            is Serving.Reads -> input.readNBytes(serving.bytes)
            is Serving.Answers -> repeat(serving.requests) {
                if (!readRequest(input, serving.pauseMs)) return
                runBlocking { delay(serving.prefillMs) }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    flush()
                }
            }
        }
    }

    /** One request, head and body; false when the connection closed first. */
    private fun readRequest(input: InputStream, pauseMs: Long): Boolean {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return false
            head.append(b.toChar())
        }
        var left = head.lines().firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toInt() ?: 0
        while (left > 0) {
            val read = input.readNBytes(if (pauseMs > 0) minOf(SLOW_CHUNK, left) else left).size
            if (read == 0) return false
            left -= read
            if (pauseMs > 0) runBlocking { delay(pauseMs) }
        }
        return true
    }

    override fun close() {
        server.close()
        sockets.forEach { val _ = runCatching { it.close() } }
    }
}

/** A self-signed certificate for 127.0.0.1, made once per run by the JDK's own keytool, never checked in. */
private object LoopbackTls {
    private const val PASS = "v4-289-loopback-only"

    private val keys: KeyStore by lazy {
        val dir = Files.createTempDirectory("v4-289-tls")
        val file = dir.resolve("upstream.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "upstream", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
            "-keystore", file.toString(), "-storepass", PASS, "-keypass", PASS,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { "keytool failed: $output" }
        try {
            KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, PASS.toCharArray()) } }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(dir)
        }
    }

    val server: SSLContext by lazy {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keys, PASS.toCharArray()) }.keyManagers
        SSLContext.getInstance("TLS").apply { init(managers, null, null) }
    }

    val trust: X509TrustManager by lazy {
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("upstream", keys.getCertificate("upstream"))
        }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
