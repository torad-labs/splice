// NEW: the concurrency load gate — N SSE turns held OPEN SIMULTANEOUSLY through a real
// HeadServer (client -> Netty head -> UpstreamClient(CIO) -> holding mock). Every turn is a
// long-lived stream, so the whole chain's concurrency ceilings bind here: Netty runningLimit,
// CIO maxConnections/maxConnectionsPerRoute, and any accidental serialization in the turn
// pipeline. The mock parks each stream on a VIRTUAL thread after its first delta and releases
// them all at once; the test asserts every stream was concurrently live (mock peak == N) and
// every stream then finished with a clean message_stop. N via SPLICE_LOAD_N (default 1000).
// The ramp semaphore bounds only the CONNECT burst (macOS listen backlog), never the held count.
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexProvider
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal HTTP/1.1 + SSE upstream built for CONCURRENCY, not scenarios: each connection gets a
 * virtual thread, answers close-delimited SSE (Connection: close — no chunked framing needed),
 * emits one delta, then parks until [release].
 */
private class HoldingSseUpstream {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val closed = AtomicBoolean(false)

    val held = AtomicInteger(0)
    val peak = AtomicInteger(0)

    @Volatile
    var release = CountDownLatch(1)

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        Thread.ofVirtual().name("hold-accept").start {
            while (!closed.get()) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                Thread.ofVirtual().start {
                    runCatching { serve(sock) }
                    runCatching { sock.close() }
                }
            }
        }
    }

    fun stop() {
        closed.set(true)
        release.countDown()
        runCatching { server.close() }
    }

    /** Consume the request head + body; returns false when the socket closed early. */
    private fun drainRequest(input: BufferedReader): Boolean {
        var contentLength = 0
        while (true) {
            val line = input.readLine() ?: return false
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        var toSkip = contentLength.toLong()
        while (toSkip > 0) {
            val skipped = input.skip(toSkip)
            if (skipped <= 0) break
            toSkip -= skipped
        }
        return true
    }

    private fun serve(sock: Socket) {
        val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        if (!drainRequest(input)) return

        val out = sock.getOutputStream()
        fun sse(json: String) {
            out.write("data: $json\n\n".toByteArray())
            out.flush()
        }
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
        sse("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")

        // Record the peak BEFORE sending the "held" delta that the client uses to count down its
        // liveness latch — else the test can sample `peak` in the window between "delta on the wire"
        // and this increment for the last turn, reading n-1 despite all n being concurrently live.
        val gate = release
        val now = held.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        sse("""{"type":"response.output_text.delta","output_index":0,"delta":"held"}""")
        try {
            // Park with a keepalive heartbeat: a gateway that ABORTS this connection (client
            // disconnect propagation) makes the next write throw, ending the hold — that is the
            // disconnect test's upstream-teardown observable.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HOLD_CAP_S)
            while (gate.count > 0 && System.nanoTime() < deadline) {
                if (gate.await(KEEPALIVE_MS, TimeUnit.MILLISECONDS)) break
                sse("""{"type":"noop"}""")
            }
        } finally {
            held.decrementAndGet()
        }

        sse("""{"type":"response.output_item.done","output_index":0}""")
        sse(
            """{"type":"response.completed","response":{"id":"rl","status":"completed",""" +
                """"output":[],"usage":{"input_tokens":1,"output_tokens":1}}}""",
        )
        out.flush()
    }

    private companion object {
        const val BACKLOG = 4096
        const val HOLD_CAP_S = 150L
        const val KEEPALIVE_MS = 200L
    }
}

private class StaticAuth : RefreshableAuthProvider {
    override suspend fun credentials() = Credentials.Bearer("tok-load", accountId = null)

    override suspend fun refresh() = credentials()

    override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerLoadTest {

    private val n = System.getenv("SPLICE_LOAD_N")?.toIntOrNull() ?: 1000
    private val port = 39777
    private lateinit var mock: HoldingSseUpstream
    private lateinit var head: HeadServer
    private val gate = InflightGate({ 0 })

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 200_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 200_000
        }
        engine {
            maxConnectionsCount = 4096
            endpoint.maxConnectionsPerRoute = 4096
            endpoint.pipelineMaxSize = 1
            endpoint.connectTimeout = 30_000
        }
    }

    @BeforeAll
    fun setUp() = runBlocking {
        mock = HoldingSseUpstream()
        val tmp = Files.createTempDirectory("head-load")
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Codex 5.6 Sol", contextWindow = 272000)),
            defaultContextWindow = 272000,
        )
        head = HeadServer(
            provider = CodexProvider(
                tuning = ProviderTuning(
                    key = "codex",
                    label = "claudex",
                    catalog = catalog,
                    pinnedModel = "gpt-5.6-sol",
                    auth = StaticAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = WatchdogBudget(120.seconds, 120.seconds, 200.seconds),
                ),
                showReasoning = "text",
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 120_000, totalTimeoutMs = 200_000, maxRetries = 2),
                gate = gate,
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
                perfStats = PerfStats(tmp.resolve("perf.jsonl")),
                log = {},
            ),
        )
        head.start()
        Thread.sleep(700) // Netty warmup
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    @Test
    fun `holds N concurrent SSE turns simultaneously and finishes them all cleanly`() = runBlocking {
        val ramp = Semaphore(RAMP_CONCURRENCY)
        mock.release = CountDownLatch(1) // re-arm regardless of test order
        val heldSeen = CountDownLatch(n)

        val turns = (1..n).map { i ->
            async(Dispatchers.IO) {
                ramp.acquire()
                var rampReleased = false
                try {
                    driveTurn(i) {
                        // first delta reached the CLIENT: this turn is live end-to-end
                        heldSeen.countDown()
                        ramp.release()
                        rampReleased = true
                    }
                } finally {
                    if (!rampReleased) {
                        heldSeen.countDown() // failure path: never wedge the ramp or the gate latch
                        ramp.release()
                    }
                }
            }
        }

        val allLive = withContext(Dispatchers.IO) { heldSeen.await(120, TimeUnit.SECONDS) }
        val peakBeforeRelease = mock.peak.get()
        mock.release.countDown()

        val finished = turns.awaitAll()
        val clean = finished.count { it.contains("event: message_stop") && !it.contains("event: error") }
        val errored = finished.count { it.contains("event: error") }
        val truncated = n - clean - errored
        println(
            "LOAD: n=$n all_live=$allLive peak_concurrent=$peakBeforeRelease " +
                "clean_finishes=$clean errored=$errored truncated=$truncated",
        )

        assertTrue(allLive, "not all $n turns became live within 120s; peak=$peakBeforeRelease")
        assertEquals(
            n,
            peakBeforeRelease,
            "expected all $n turns HELD OPEN simultaneously; ceiling hit at $peakBeforeRelease",
        )
        assertEquals(n, clean, "every held turn must finish with message_stop and no error frame")
    }

    @Test
    fun `client disconnects mid-stream free every gate slot and abort upstream`() = runBlocking {
        // The audit's top finding: Esc'd turns kept the upstream burning and the slot pinned
        // until the watchdog cap. This drives N real turns, kills the CLIENTS mid-hold, and
        // asserts both halves of the fix: gate slots return AND upstream connections die.
        val dn = DISCONNECT_N
        mock.release = CountDownLatch(1) // re-arm regardless of test order
        val heldSeen = CountDownLatch(dn)
        val turns = (1..dn).map { i ->
            async(Dispatchers.IO) {
                runCatching {
                    driveTurn(i) { heldSeen.countDown() }
                }
            }
        }
        val allLive = withContext(Dispatchers.IO) { heldSeen.await(60, TimeUnit.SECONDS) }
        assertTrue(allLive, "expected $dn live streams before disconnecting")
        assertEquals(dn, gate.snapshot().inflight, "gate must hold $dn slots mid-stream")

        // Kill every CLIENT — the gateway must notice and tear down its upstream legs.
        turns.forEach { it.cancel() }

        val slotsFreed = waitFor(30_000) { gate.snapshot().inflight == 0 }
        assertTrue(slotsFreed, "gate slots leaked after client disconnects: ${gate.snapshot()}")
        val upstreamTorn = waitFor(30_000) { mock.held.get() == 0 }
        assertTrue(upstreamTorn, "upstream connections still parked: ${mock.held.get()} — cancel did not propagate")
    }

    private suspend fun waitFor(capMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            kotlinx.coroutines.delay(100)
        }
        return cond()
    }

    /** Run one turn to stream end; [onHeld] fires once when the first delta frame arrives. */
    private suspend fun driveTurn(i: Int, onHeld: () -> Unit): String {
        val body = """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
            "system":"load","messages":[{"role":"user","content":"hold $i"}]}"""
        val sb = StringBuilder()
        var held = false
        withTimeout(190_000) {
            client.preparePost("http://127.0.0.1:$port/v1/messages") {
                setBody(body)
            }.execute { resp ->
                val ch = resp.bodyAsChannel()
                while (true) {
                    val line = ch.readUTF8Line() ?: break
                    sb.append(line).append('\n')
                    if (!held && line.contains("\"held\"")) {
                        held = true
                        onHeld()
                    }
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        const val RAMP_CONCURRENCY = 96
        const val DISCONNECT_N = 100
    }
}
