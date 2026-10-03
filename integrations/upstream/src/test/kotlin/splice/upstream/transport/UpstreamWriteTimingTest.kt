// NEW: V4-456 — actual OkHttp write/read boundaries, retry isolation, and private context removal.
package splice.upstream.transport

import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.okhttp.OkHttpConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class UpstreamWriteTimingTest {
    @Test
    fun `a retry records the final wire attempt and never sends its timing handoff header`() {
        val now = AtomicLong(100)
        val perf = TurnPerf { now.get() }
        perf.recordArrival(70)
        val requests = AtomicInteger()
        val headerNames = CopyOnWriteArrayList<Set<String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            headerNames += exchange.requestHeaders.keys.toSet()
            val attempt = requests.incrementAndGet()
            val answer = if (attempt == 1) "retry" else "done"
            exchange.sendResponseHeaders(if (attempt == 1) 503 else 200, answer.length.toLong())
            exchange.responseBody.use { it.write(answer.toByteArray()) }
        }
        server.start()
        val client = UpstreamTransport().defaultClient(10_000)
        val config = client.engine.config
        check(config is OkHttpConfig)
        config.addNetworkInterceptor(ScriptedWireDelay(now))
        try {
            val upstream = UpstreamClient(10_000, maxRetries = 2, client = client, backoff = { _, _ ->
                now.addAndGet(500)
            })
            val context = PostContext(
                "http://127.0.0.1:${server.address.port}/",
                TimingAuth(),
                { emptyMap() },
                perf = perf,
            )
            val answer = runBlocking { upstream.posted(context, "{}") { it.bodyTextLimited(100) } }
            assertEquals("done", answer)
            assertEquals(2, requests.get())
            assertEquals(660L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS])
            assertEquals(30L, perf.snapshot().counters[PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS])
            assertEquals(1L, perf.snapshot().counters[PerfKeys.RETRIES])
            assertFalse(headerNames.any { names -> names.any { it.equals(UPSTREAM_TIMING_HEADER, ignoreCase = true) } })
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun `prompt positive Source reads stay separate from a caller decode stall`() {
        val now = AtomicLong(100)
        val perf = TurnPerf(ElapsedClock { now.get() }, WallClock { 1_000_000 })
        val server = delayedHeadersServer(CompletableDeferred(), CountDownLatch(0))
        val client = UpstreamTransport().defaultClient(10_000)
        val config = client.engine.config
        check(config is OkHttpConfig)
        config.addNetworkInterceptor(SplitReadResponse(now))
        try {
            val context = PostContext(
                "http://127.0.0.1:${server.address.port}/",
                TimingAuth(),
                { emptyMap() },
                perf = perf,
            )
            val answer = runBlocking {
                UpstreamClient(10_000, maxRetries = 1, client = client).posted(context, "{}") {
                    val body = it.bodyTextLimited(100)
                    now.addAndGet(5_000)
                    body
                }
            }
            assertEquals("done", answer)
            val counters = perf.snapshot().counters
            assertEquals(10L, counters["up_wire_gap_max_ms"])
            assertEquals(1_000_010L, counters["up_wire_gap_max_start_epoch_ms"])
            assertEquals(10L, counters["up_read_wait_max_ms"])
            assertEquals(0L, counters["up_read_idle_max_ms"])
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun `delayed socket headers and the Ktor receipt hop have distinct arrival clocks`() {
        val now = AtomicLong(100)
        val perf = TurnPerf { now.get() }
        perf.recordArrival(70)
        val seen = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = delayedHeadersServer(seen, release)
        val client = UpstreamTransport().defaultClient(10_000)
        val config = client.engine.config
        check(config is OkHttpConfig)
        config.addInterceptor(KtorReceiptHop(now))
        try {
            val upstream = UpstreamClient(10_000, maxRetries = 1, client = client)
            val context = PostContext(
                "http://127.0.0.1:${server.address.port}/",
                TimingAuth(),
                { emptyMap() },
                perf = perf,
            )
            val answer = runBlocking {
                val pending = async { upstream.posted(context, "{}") { it.bodyTextLimited(100) } }
                withTimeout(5_000) {
                    seen.await()
                    while (perf.snapshot().counters[PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS] == null) yield()
                }
                assertNull(perf.snapshot().counters["arrival_to_upstream_headers_start_ms"])
                now.set(1170)
                release.countDown()
                pending.await()
            }
            assertEquals("done", answer)
            val counters = perf.snapshot().counters
            assertEquals(30L, counters[PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS])
            assertEquals(1100L, counters["arrival_to_upstream_headers_start_ms"])
            assertEquals(300L, counters["upstream_headers_start_to_ktor_headers_ms"])
            assertEquals(1370L, counters[PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS])
        } finally {
            release.countDown()
            client.close()
            server.stop(0)
        }
    }
}

private fun delayedHeadersServer(seen: CompletableDeferred<Unit>, release: CountDownLatch): HttpServer =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            seen.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write("done".toByteArray()) }
        }
        start()
    }

private class SplitReadResponse(private val now: AtomicLong) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        return response.newBuilder().body(SingleByteReads(response.body, now)).build()
    }
}

private class SingleByteReads(private val body: ResponseBody, now: AtomicLong) : ResponseBody() {
    private val input = object : ForwardingSource(body.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val count = super.read(sink, minOf(byteCount, 1))
            if (count > 0) now.addAndGet(10)
            return count
        }
    }.buffer()

    override fun contentType(): MediaType? = body.contentType()
    override fun contentLength(): Long = body.contentLength()
    override fun source(): BufferedSource = input
}

private class KtorReceiptHop(private val now: AtomicLong) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        now.addAndGet(300)
        return response
    }
}

private class TimingAuth : RefreshableAuthProvider {
    override suspend fun credentials() = Credentials.Bearer("synthetic", null)
    override suspend fun refresh() = credentials()
    override suspend fun describe() = AuthDescription(true, "synthetic", emptyMap())
}

private class ScriptedWireDelay(private val now: AtomicLong) : okhttp3.Interceptor {
    private val attempts = AtomicInteger()

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val attempt = attempts.incrementAndGet()
        val request = chain.request()
        val body = checkNotNull(request.body)
        val delayed = object : RequestBody() {
            override fun contentType(): MediaType? = body.contentType()
            override fun contentLength(): Long = body.contentLength()
            override fun writeTo(sink: BufferedSink) {
                now.addAndGet(if (attempt == 1) 40 else 70)
                body.writeTo(sink)
            }
        }
        val response = chain.proceed(request.newBuilder().method(request.method, delayed).build())
        return response.newBuilder().body(DelayedRead(response.body, now, if (attempt == 1) 20 else 30)).build()
    }
}

private class DelayedRead(body: ResponseBody, now: AtomicLong, delay: Long) : ResponseBody() {
    private val type = body.contentType()
    private val length = body.contentLength()
    private var first = true
    private val input = object : ForwardingSource(body.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0 && first) {
                first = false
                now.addAndGet(delay)
            }
            return read
        }
    }.buffer()

    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = length
    override fun source(): BufferedSource = input
}
