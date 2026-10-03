package splice.head

import io.ktor.client.engine.okhttp.OkHttpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.head.admission.RequestMaterializationGate
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import splice.upstream.transport.UpstreamTransport
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.seconds

private const val TIMING_WAIT_MS = 300L
private const val TIMING_TIMEOUT_MS = 10_000L
private const val ADMIT_KEY = "admit_wait_ms"
private const val LEASE_KEY = "lease_wait_ms"
private const val PREP_KEY = "prep_ms"
private const val CLIENT_BYTE_KEY = "arrival_to_first_client_byte_ms"

class RequestStartTimingTest {
    @Test
    fun `a delayed upstream request write reaches its persisted arrival to write duration`(
        @TempDir root: Path,
    ) = runBlocking<Unit> {
        DelayedUpstreamWrite().use { write ->
            TimingRig(root, write = write).use { rig ->
                rig.start()
                val response = async(Dispatchers.IO) { timingPost(rig.port, "timed") }
                try {
                    assertTrue(write.entered.await(TIMING_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    delay(TIMING_WAIT_MS)
                    write.advance(TIMING_WAIT_MS)
                    write.release.countDown()
                    assertTrue(response.await().contains("message_stop"))
                    val row = timingRow(root)
                    val written = duration(row, "arrival_to_upstream_write_ms")
                    assertTrue(written >= TIMING_WAIT_MS, row.toString())
                    assertTrue(duration(row, PREP_KEY) < written, row.toString())
                    assertTrue(duration(row, "upstream_write_to_first_byte_ms") >= 0, row.toString())
                    assertEquals(0L, duration(row, "retries"))
                } finally {
                    write.release.countDown()
                }
            }
        }
    }

    @Test
    fun `queued inflight time is numeric and separate from preparation`(@TempDir root: Path) = runBlocking<Unit> {
        TimingRig(root, maxInflight = 1).use { rig ->
            rig.start()
            val first = async(Dispatchers.IO) { timingPost(rig.port, "blocked") }
            try {
                assertTrue(rig.provider.entered.await(TIMING_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                val second = async(Dispatchers.IO) { timingPost(rig.port, "timed") }
                awaitTiming { rig.gate.snapshot().queued == 1 }
                delay(TIMING_WAIT_MS)
                rig.provider.release.countDown()
                assertTrue(first.await().contains("message_stop"))
                assertTrue(second.await().contains("message_stop"))
                val row = timingRow(root)
                assertTrue(duration(row, ADMIT_KEY) >= TIMING_WAIT_MS, row.toString())
                assertTrue(duration(row, CLIENT_BYTE_KEY) >= duration(row, ADMIT_KEY), row.toString())
                duration(row, LEASE_KEY)
                duration(row, PREP_KEY)
            } finally {
                rig.provider.release.countDown()
            }
        }
    }

    @Test
    fun `heap lease queue time is numeric and excluded from preparation`(@TempDir root: Path) = runBlocking<Unit> {
        val budget = splice.core.memory.HeapWeights.request(timingBody("blocked").toByteArray().size.toLong())
        TimingRig(root, heapBytes = budget).use { rig ->
            rig.start()
            val first = async(Dispatchers.IO) { timingPost(rig.port, "blocked") }
            try {
                assertTrue(rig.provider.entered.await(TIMING_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                val second = async(Dispatchers.IO) { timingPost(rig.port, "timed") }
                awaitTiming { rig.gate.snapshot().inflight == 2 }
                assertEquals(null, rig.heap.tryWithLease(1) { "capacity positive control" })
                delay(TIMING_WAIT_MS)
                rig.provider.release.countDown()
                assertTrue(first.await().contains("message_stop"))
                assertTrue(second.await().contains("message_stop"))
                val row = timingRow(root)
                assertTrue(duration(row, LEASE_KEY) >= TIMING_WAIT_MS, row.toString())
                assertTrue(duration(row, CLIENT_BYTE_KEY) >= duration(row, LEASE_KEY), row.toString())
                assertTrue(duration(row, PREP_KEY) < duration(row, LEASE_KEY), row.toString())
                duration(row, ADMIT_KEY)
            } finally {
                rig.provider.release.countDown()
            }
        }
    }

    @Test
    fun `a collected reply records its actual client write before publishing the perf row`(
        @TempDir root: Path,
    ) = runBlocking<Unit> {
        TimingRig(root, prepDelay = true).use { rig ->
            rig.start()
            assertTrue(timingPost(rig.port, "timed", stream = false).contains("\"content\""))
            val row = timingRow(root)
            assertTrue(duration(row, CLIENT_BYTE_KEY) >= duration(row, PREP_KEY), row.toString())
            duration(row, ADMIT_KEY)
            duration(row, LEASE_KEY)
        }
    }

    @Test
    fun `injected synchronous preparation delay reaches both preparation and first client byte`(
        @TempDir root: Path,
    ) = runBlocking<Unit> {
        TimingRig(root, prepDelay = true).use { rig ->
            rig.start()
            assertTrue(timingPost(rig.port, "timed").contains("message_stop"))
            val row = timingRow(root)
            assertTrue(duration(row, PREP_KEY) >= TIMING_WAIT_MS, row.toString())
            assertTrue(duration(row, CLIENT_BYTE_KEY) >= duration(row, PREP_KEY), row.toString())
            duration(row, ADMIT_KEY)
            duration(row, LEASE_KEY)
            assertTrue(row["first_byte"]?.jsonPrimitive?.longOrNull != null, "legacy first_byte remains numeric")
        }
    }
}

private class TimingRig(
    private val root: Path,
    maxInflight: Int = 0,
    heapBytes: Long = 0,
    prepDelay: Boolean = false,
    write: DelayedUpstreamWrite? = null,
) : AutoCloseable {
    private val upstream = MockChatGptUpstream()
    val provider = TimingProvider(timingProvider(upstream.baseUrl), prepDelay)
    val gate = InflightGate(maxInflight = { maxInflight }, maxQueued = { 4 })
    val heap = RequestMaterializationGate(heapBudgetBytes = heapBytes)
    private val head = HeadServer(
        provider,
        0,
        headDeps(
            root,
            upstream = write?.upstream ?: UpstreamClient(totalTimeoutMs = 30_000L, maxRetries = 2),
            gate = gate,
            log = {},
            seams = HeadDeps.HeadSeams(
                requestMaterializationGate = heap,
                clock = write?.clock ?: ElapsedClock(MonoClock::nowMs),
            ),
        ),
    )
    val port: Int get() = head.port

    suspend fun start() = head.start()

    override fun close() {
        provider.release.countDown()
        runBlocking { head.stop() }
        upstream.stop()
        assertTrue(AsyncFileIo.drain(), "real perf rows must finish before fixture deletion")
    }
}

/** Holds the actual OkHttp body writer, not preparation or the mock server's response. */
private class DelayedUpstreamWrite : AutoCloseable {
    private val now = AtomicLong(100)
    val clock = ElapsedClock { now.get() }
    fun advance(ms: Long) { now.addAndGet(ms) }
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val client = UpstreamTransport().defaultClient(TIMING_TIMEOUT_MS)
    val upstream = UpstreamClient(totalTimeoutMs = TIMING_TIMEOUT_MS, maxRetries = 1, client = client)

    init {
        val config = client.engine.config
        check(config is OkHttpConfig)
        config.addNetworkInterceptor { chain ->
            val request = chain.request()
            val body = checkNotNull(request.body)
            val held = object : RequestBody() {
                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                override fun writeTo(sink: BufferedSink) {
                    entered.countDown()
                    check(release.await(TIMING_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    body.writeTo(sink)
                }
            }
            chain.proceed(request.newBuilder().method(request.method, held).build())
        }
    }

    override fun close() {
        release.countDown()
        client.close()
    }
}

private class TimingProvider(private val base: Provider, private val prepDelay: Boolean) : Provider by base {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        when (body.raw["system"]?.jsonPrimitive?.content) {
            "blocked" -> {
                entered.countDown()
                assertTrue(release.await(TIMING_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            }
            "timed" -> if (prepDelay) CountDownLatch(1).await(TIMING_WAIT_MS, TimeUnit.MILLISECONDS)
        }
        return base.buildTurn(body, compact, sessionId)
    }
}

private class TimingAuth : RefreshableAuthProvider {
    override suspend fun credentials() = Credentials.Bearer("synthetic", accountId = null)
    override suspend fun refresh() = credentials()
    override suspend fun describe() = AuthDescription(true, "synthetic", emptyMap())
}

private fun timingProvider(url: String): Provider = TestResponsesProvider(
    tuning = ProviderTuning(
        key = "synthetic",
        label = "synthetic",
        catalog = ModelCatalog(
            discoveryPrefix = "synthetic-",
            models = listOf(ModelEntry("synthetic", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
        ),
        pinnedModel = "synthetic",
        auth = TimingAuth(),
        baseUrl = url,
        watchdog = WatchdogBudget(5.seconds, 5.seconds, 10.seconds),
    ),
    showReasoning = ReasoningDisplay.OFF,
    replayReasoning = false,
    configEffort = "high",
    configSummary = null,
)

private suspend fun awaitTiming(condition: () -> Boolean) = withTimeout(TIMING_TIMEOUT_MS) {
    // ast-grep-ignore: kt-tests-no-wall-clock -- polls real gate state under a withTimeout deadline, never a fixed wait.
    while (!condition()) delay(5)
}

private fun timingBody(label: String, stream: Boolean = true): String =
    """{"model":"synthetic-synthetic","stream":$stream,"max_tokens":64,"system":"$label","messages":[{"role":"user","content":"synthetic timing"}]}"""

private fun timingPost(port: Int, label: String, stream: Boolean = true): String {
    val body = timingBody(label, stream)
    return Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = TIMING_TIMEOUT_MS.toInt()
        val request = "POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer test-inference-token\r\nx-claude-code-session-id: $label\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader().readText()
    }
}

/** Polls with a deadline, never a sleep for a duration (kt-tests-no-wall-clock). A collected reply publishes
 *  its row in collect's finally, after the client write returns (CollectPerf.publish), so the client can
 *  read the closed response before the row reaches the file lane: one drain and one read raced it (CI run
 *  37103873666, NoSuchFileException on perf.jsonl). */
private fun timingRow(root: Path): JsonObject {
    val file = root.resolve("perf.jsonl")
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ROW_WAIT_SECONDS)
    while (true) {
        assertTrue(AsyncFileIo.drain())
        val lines = if (Files.exists(file)) Files.readAllLines(file) else emptyList()
        val timed = lines.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["session_id"]?.jsonPrimitive?.content == "timed" }
        if (timed.isNotEmpty()) return timed.single()
        check(System.nanoTime() < deadline) { "no perf row for the timed session within $ROW_WAIT_SECONDS s" }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ROW_POLL_MS))
    }
}

// why: the row lands within milliseconds of the reply; 10 s only bounds a CI runner under load.
private const val ROW_WAIT_SECONDS = 10L

// why: a short poll keeps the wait close to the row's real arrival without spinning.
private const val ROW_POLL_MS = 5L

private fun duration(row: JsonObject, key: String): Long {
    val value = row[key]?.jsonPrimitive
    assertTrue(value != null && !value.isString && value.longOrNull != null, "$key must be a numeric duration: $row")
    return checkNotNull(value?.longOrNull)
}
