// NEW: V4-456 — blocked request work cannot pin Netty and queue another session's turn.
package splice.head

import com.sun.net.httpserver.HttpServer
import io.netty.util.concurrent.FastThreadLocalThread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.ClientAuthProvider
import splice.core.auth.ForeignHostLog
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.head.admission.AdmissionGate
import splice.head.admission.AdmissionResponses
import splice.head.admission.AdmissionTelemetry
import splice.head.admission.AdmissionWindow
import splice.head.admission.HeadAdmission
import splice.head.compaction.CompactionReplay
import splice.head.turn.TurnDriver
import splice.head.turn.TurnPreparation
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.RoundInterceptor
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

// Real time is the observable: virtual time cannot measure another connection's socket-delivery gap.
private const val BLOCK_MS = 2_000L
private const val GAP_LIMIT_MS = 100L
private const val CLIENT_TIMEOUT_MS = 15_000
private const val UPSTREAM_INTERVAL_MS = 10L
private const val UPSTREAM_DELTAS = 350
private const val WARM_DELTAS = 5

class HeadEngineDispatchTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a synchronous sibling request build cannot stall a steady stream`(reporter: TestReporter) = runBlocking {
        interference(reporter, collectWait = false)
    }

    @Test
    fun `a blocking collect drive cannot queue another session behind its call thread`(
        reporter: TestReporter,
    ) = runBlocking {
        interference(reporter, collectWait = true)
    }

    @Test
    fun `the local token estimate also leaves the Netty call thread`() = runBlocking {
        DispatchUpstream().use { upstream ->
            val onNetty = CompletableFuture<Boolean>()
            val deps = headDeps(
                tmp,
                log = { line ->
                    if (line.contains("count_tokens estimate=")) {
                        onNetty.complete(Thread.currentThread() is FastThreadLocalThread)
                    }
                },
            )
            val engine = dispatchEngine(dispatchProvider(upstream.baseUrl), deps)
            engine.start(callThreads = 1)
            try {
                Socket("127.0.0.1", engine.port).use { socket ->
                    socket.soTimeout = CLIENT_TIMEOUT_MS
                    writePost(socket, engine.port, "count", path = "/v1/messages/count_tokens")
                    val reply = socket.getInputStream().bufferedReader().readText()
                    assertTrue(reply.contains("input_tokens"), "the local estimate must still be served")
                }
                val ranOnNetty = onNetty.get(CLIENT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                assertTrue(!ranOnNetty, "count_tokens work must not occupy a Netty call thread: $ranOnNetty")
            } finally {
                engine.stop()
                assertTrue(AsyncFileIo.drain())
            }
        }
    }

    @Test
    fun `eight blocked preparations leave a ninth session free to start`(reporter: TestReporter) = runBlocking {
        DispatchUpstream().use { upstream ->
            val blocker = DispatchBlocker(collectWait = false, sessions = 8)
            val deps = headDeps(tmp, log = {})
            val provider = DispatchProvider(dispatchProvider(upstream.baseUrl), blocker)
            val engine = dispatchEngine(provider, deps)
            engine.start(callThreads = 1)
            try {
                observeConcurrent(engine.port, blocker, reporter)
            } finally {
                engine.stop()
                assertTrue(AsyncFileIo.drain())
            }
        }
    }

    private fun observeConcurrent(port: Int, blocker: DispatchBlocker, reporter: TestReporter) {
        // Warm the real round, including lazy transport/class initialization, before measuring queueing.
        assertTrue(postAndRead(port, "warm", stream = false).contains("\"content\""))
        val started = System.nanoTime()
        val replies = List(8) { postAsync(port, "block-$it", stream = true) }
        val together = blocker.entered.await(1_000, TimeUnit.MILLISECONDS)
        reporter.publishEntry("concurrent_preparations_entered", (8 - blocker.entered.count).toString())
        assertTrue(together, "all eight preparations must enter before any two-second block ends")
        val startMs = firstEventMs(port)
        assertTrue(blocker.finished.await(3_000, TimeUnit.MILLISECONDS), "all eight preparations must finish together")
        val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        reporter.publishEntry("eight_preparations_elapsed_ms", totalMs.toString())
        reporter.publishEntry("ninth_turn_start_ms", startMs.toString())
        assertTrue(totalMs < BLOCK_MS + 1_000, "eight_preparations_elapsed_ms=$totalMs")
        assertTrue(startMs < GAP_LIMIT_MS, "ninth_turn_start_ms=$startMs; limit=$GAP_LIMIT_MS")
        assertTrue(!blocker.onNetty, "preparations must not occupy Netty call threads")
        replies.forEach { reply ->
            assertTrue(reply.get(CLIENT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS).contains("message_stop"))
        }
    }

    private fun observe(port: Int, blocker: DispatchBlocker, reporter: TestReporter) {
        DispatchStream(port).use { stream ->
            stream.start()
            assertTrue(stream.warm.await(CLIENT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
            val sibling = postAsync(port, "block", stream = !blocker.collectWait)
            assertTrue(blocker.entered.await(CLIENT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
            val startedMs = firstEventMs(port)
            val siblingBody = sibling.get(CLIENT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            assertTrue(siblingBody.contains(if (blocker.collectWait) "\"content\"" else "message_stop"))
            val delivered = stream.finished.get(CLIENT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            assertTrue(delivered.contains("message_stop"), "the streaming call must end cleanly")
            val gapMs = TimeUnit.NANOSECONDS.toMillis(stream.maxGapNs.get())
            val kind = if (blocker.collectWait) "collect_wait" else "request_build"
            reporter.publishEntry("${kind}_stream_gap_ms", gapMs.toString())
            reporter.publishEntry("${kind}_next_turn_start_ms", startedMs.toString())
            reporter.publishEntry("sibling_block_ms", blocker.elapsedMs.toString())
            reporter.publishEntry("sibling_on_netty_thread", blocker.onNetty.toString())
            assertTrue(blocker.elapsedMs >= BLOCK_MS, "the injected block must actually run")
            assertTrue(gapMs < GAP_LIMIT_MS, "${kind}_stream_gap_ms=$gapMs; limit=$GAP_LIMIT_MS")
            assertTrue(startedMs < GAP_LIMIT_MS, "${kind}_next_turn_start_ms=$startedMs; limit=$GAP_LIMIT_MS")
            assertTrue(!blocker.onNetty, "request work must not occupy a Netty call thread")
        }
    }

    private suspend fun interference(reporter: TestReporter, collectWait: Boolean) {
        DispatchUpstream().use { upstream ->
            val blocker = DispatchBlocker(collectWait)
            val deps = headDeps(tmp, log = {})
            val provider = DispatchProvider(dispatchProvider(upstream.baseUrl), blocker)
            val engine = dispatchEngine(provider, deps)
            engine.start(callThreads = 1)
            try {
                observe(engine.port, blocker, reporter)
            } finally {
                engine.stop()
                assertTrue(AsyncFileIo.drain(), "queued writes must settle before temporary files are deleted")
            }
        }
    }
}

private class DispatchBlocker(val collectWait: Boolean, private val sessions: Int = 1) {
    val entered = CountDownLatch(sessions)
    val finished = CountDownLatch(sessions)
    private val elapsed = AtomicLong()
    private val netty = AtomicBoolean()
    val elapsedMs: Long get() = elapsed.get()
    val onNetty: Boolean get() = netty.get()

    fun block() {
        val started = System.nanoTime()
        if (Thread.currentThread() is FastThreadLocalThread) netty.set(true)
        entered.countDown()
        if (collectWait || sessions > 1) {
            CountDownLatch(1).await(BLOCK_MS, TimeUnit.MILLISECONDS)
        } else {
            val until = started + TimeUnit.MILLISECONDS.toNanos(BLOCK_MS)
            while (System.nanoTime() < until) Thread.onSpinWait()
        }
        elapsed.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), ::maxOf)
        finished.countDown()
    }
}

private class DispatchProvider(private val base: Provider, private val blocker: DispatchBlocker) : Provider by base {
    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val blocked = body.raw["system"]?.jsonPrimitive?.content?.startsWith("block") == true
        if (blocked && !blocker.collectWait) blocker.block()
        val built = base.buildTurn(body, compact, sessionId)
        return if (blocked && blocker.collectWait) {
            built.copy(
                roundInterceptor = RoundInterceptor { wire, _, post ->
                    blocker.block()
                    post(wire)
                },
            )
        } else {
            built
        }
    }
}

private fun dispatchProvider(baseUrl: String): Provider = TestResponsesProvider(
    tuning = ProviderTuning(
        key = "synthetic",
        label = "synthetic",
        catalog = ModelCatalog(
            discoveryPrefix = "synthetic-",
            models = listOf(ModelEntry("synthetic", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
        ),
        pinnedModel = "synthetic",
        auth = ClientAuthProvider("synthetic"),
        baseUrl = baseUrl,
        watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
    ),
    showReasoning = ReasoningDisplay.OFF,
    replayReasoning = false,
    configEffort = "high",
    configSummary = null,
)

// The real composition, with only the engine exposed so the call group has deterministic affinity.
private fun dispatchEngine(provider: Provider, deps: HeadDeps): HeadEngine {
    val replay = CompactionReplay(deps.stores.compactionRecordings)
    val driver = TurnDriver(provider, deps, replay)
    val window = AdmissionWindow().apply { open() }
    val responses = AdmissionResponses()
    val auth = ClientAuth(deps, responses, ForeignHostLog("synthetic", deps.log), forwardsOnly = true)
    val reader = RequestBodyReader(deps.policy.requestReadTimeoutMs)
    val parse = AnthropicBodyParse()
    val gate = AdmissionGate(
        provider,
        deps,
        window,
        responses,
        CompactionPreflight(provider.catalog, deps.stores.perfStats),
    )
    val diagnostics = HeadDiagnostics(provider, deps.gate, driver, deps.stores.wireTap)
    val admission = HeadAdmission(
        deps,
        auth,
        gate,
        AdmissionTelemetry(deps.gate, deps.seams.clock),
        TurnPreparation(provider, deps, reader, parse, auth, replay),
        responses,
        driver,
    )
    val count = CountTokens(provider, deps, auth, gate, reader, parse, responses)
    return HeadEngine(provider, 0, deps.log, diagnostics, auth, admission, count)
}

private class DispatchStream(private val port: Int) : AutoCloseable {
    private val socket = Socket("127.0.0.1", port).apply { soTimeout = CLIENT_TIMEOUT_MS }
    val warm = CountDownLatch(1)
    val finished = CompletableFuture<String>()
    val maxGapNs = AtomicLong()

    fun start() {
        writePost(socket, port, "steady")
        Thread.ofVirtual().start {
            try {
                val text = StringBuilder()
                var previous = 0L
                var deltas = 0
                socket.getInputStream().bufferedReader().useLines { lines ->
                    for (line in lines) {
                        text.appendLine(line)
                        if (line.startsWith("event: content_block_delta")) {
                            val now = System.nanoTime()
                            if (previous != 0L) maxGapNs.accumulateAndGet(now - previous, ::maxOf)
                            previous = now
                            deltas += 1
                            if (deltas == WARM_DELTAS) warm.countDown()
                        }
                    }
                }
                finished.complete(text.toString())
            } catch (failure: Exception) {
                finished.completeExceptionally(failure)
            }
        }
    }

    override fun close() {
        socket.close()
    }
}

private fun postAsync(port: Int, system: String, stream: Boolean): CompletableFuture<String> {
    val result = CompletableFuture<String>()
    Thread.ofVirtual().start {
        try {
            result.complete(postAndRead(port, system, stream))
        } catch (failure: Exception) {
            result.completeExceptionally(failure)
        }
    }
    return result
}

private fun postAndRead(port: Int, system: String, stream: Boolean): String = Socket("127.0.0.1", port).use { socket ->
    socket.soTimeout = CLIENT_TIMEOUT_MS
    writePost(socket, port, system, stream)
    socket.getInputStream().bufferedReader().readText()
}

private fun firstEventMs(port: Int): Long = Socket("127.0.0.1", port).use { socket ->
    socket.soTimeout = CLIENT_TIMEOUT_MS
    val started = System.nanoTime()
    writePost(socket, port, "next")
    val first = socket.getInputStream().bufferedReader().lineSequence().first { it.startsWith("event:") }
    assertTrue(first.startsWith("event:"), "the first byte must belong to an SSE event")
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
}

private fun writePost(
    socket: Socket,
    port: Int,
    system: String,
    stream: Boolean = true,
    path: String = "/v1/messages",
) {
    val body = """{"model":"synthetic-synthetic","stream":$stream,"max_tokens":512,"system":"$system","messages":[{"role":"user","content":"synthetic"}]}"""
    val bytes = body.toByteArray(Charsets.UTF_8)
    val headers = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
        "Authorization: Bearer test-inference-token\r\nContent-Type: application/json\r\n" +
        "X-Claude-Code-Session-Id: synthetic-$system\r\n" +
        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
    socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
    socket.getOutputStream().write(bytes)
    socket.getOutputStream().flush()
}

private class DispatchUpstream : AutoCloseable {
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val closed = AtomicBoolean(false)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = workers
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                fun event(payload: String) {
                    output.write("data: $payload\n\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                event("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")
                repeat(UPSTREAM_DELTAS) {
                    if (!closed.get()) {
                        event("""{"type":"response.output_text.delta","output_index":0,"delta":"x"}""")
                        CountDownLatch(1).await(UPSTREAM_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    }
                }
                event("""{"type":"response.output_item.done","output_index":0}""")
                event(
                    """{"type":"response.completed","response":{"id":"synthetic","status":"completed","output":[],"usage":{"input_tokens":1,"output_tokens":350}}}""",
                )
            }
            exchange.close()
        }
        server.start()
    }

    override fun close() {
        closed.set(true)
        server.stop(0)
        workers.close()
    }
}
