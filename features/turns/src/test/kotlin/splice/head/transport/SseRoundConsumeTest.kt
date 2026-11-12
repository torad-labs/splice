// NEW (DR-90): the per-ATTEMPT zero-event baseline. UpstreamClient invokes the consume lambda once
// per attempt and REUSES the round's WsRoundInputs across stream reissues (G5) — so a round-scoped
// events baseline counts attempt 1's parsed frames against a reissued attempt, and the G2
// zero-event reclassify is skipped exactly when it matters: the reissue came back as a dead-head
// body (HTML login 200, zero SSE frames). Driven at the SseRoundConsume seam because the reissue
// itself needs a mid-stream connection RST the in-process mock cannot force (HeadServerReviewTest's
// torn-stream note); calling consume twice with the same inputs IS the UpstreamClient contract.
package splice.head.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.Tear
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.UpstreamGapEnd
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.dialect.responses.ReasoningSettings
import splice.head.MockChatGptUpstream
import splice.head.RecordingSink2
import splice.head.TestResponsesProvider
import splice.head.admission.TurnQuota
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.testVoice
import splice.head.turn.TurnDrive
import splice.head.turn.TurnInputs
import splice.head.turn.TurnSlot
import splice.head.turn.TurnTelemetry
import splice.head.usage.OutputClamp
import splice.head.usage.UsageStore
import splice.head.wire.ClientChannel
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.TurnTerminal
import splice.upstream.BuiltTurn
import splice.upstream.ClientFrameEmitted
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.RetryBackoff
import splice.upstream.RetryNotice
import splice.upstream.RoundBody
import splice.upstream.RoundResult
import splice.upstream.StreamRead
import splice.upstream.StreamTranslator
import splice.upstream.TurnSignals
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.sse.WireSink
import splice.upstream.transport.BodyChannelSource
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import splice.upstream.transport.UpstreamResponse
import splice.upstream.transport.UpstreamTransport
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

private class ConsumeFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-consume", "acct-consume")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** consume() opens the turn and may emit; none of it is under test — the classify verdict is. */
private class NoopTerminal : TurnTerminal, WireSink by RecordingSink2() {
    override val hasEnded: Boolean = false

    override suspend fun ensureStarted() = Unit
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override suspend fun emitError(cause: FailureCause) = Unit
    override fun abandon() = Unit
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseRoundConsumeTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private lateinit var tmp: Path

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        tmp = tempDir
    }

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun provider(baseUrl: String = mock.baseUrl): Provider = TestResponsesProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = "codex", label = "claudex"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = ConsumeFakeAuth(),
            locations = ProviderLocations(baseUrl = baseUrl),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
    )

    private suspend fun drive(
        budget: WatchdogBudget = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
        perf: TurnPerf = TurnPerf(),
        emitter: TurnTerminal = NoopTerminal(),
    ): TurnDrive = TurnDrive(
        inputs = TurnInputs(
            built = BuiltTurn(
                requestBody = buildJsonObject { },
                meta = TurnMeta(
                    compact = false,
                    reasoning = TurnReasoning(
                        showReasoning = ReasoningDisplay.TEXT,
                        effort = "high",
                        summary = "detailed",
                        budgetTokens = null,
                    ),
                    route = TurnRoute(
                        stream = true,
                        originalModel = "claude-codex--gpt-5.6-sol",
                        upstreamModel = "gpt-5.6-sol",
                        clientMaxTokens = 100,
                    ),
                ),
                extraHeaders = emptyMap(),
                toolSearch = null,
            ),
            slot = TurnSlot(InflightGate(LiveLimit { 1 }).admittedSlot()) {},
            t0 = 0,
            perf = perf,
            trace = null,
            voice = testVoice,
        ),
        emitter = emitter,
        watchdog = TurnWatchdog(budget),
        pipeline = TurnPipeline(
            CompactStats(tmp.resolve("compact-dr90.jsonl")),
            log = {},
            clampOutput = OutputClamp { it },
        ),
        signals = RunnerSignals(),
        channel = ClientChannel(
            ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = {}),
            Mutex(),
            AtomicBoolean(false),
        ),
    )

    /** One consume's outcome: these arms read the round's answer, so an early ending fails them loudly. */
    private suspend fun SseRoundConsume.read(inputs: WsRoundInputs, resp: UpstreamResponse): TurnOutcome =
        (consume(inputs, resp) as StreamRead.Read).value

    private suspend fun fetch(scenario: String): UpstreamResponse =
        UpstreamResponse(
            client.post("${mock.baseUrl}/v1/responses") {
                setBody("""{"instructions":"SCENARIO:$scenario"}""")
            },
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `upstream silence is measured after the first event and names only its ending kind`() = runTest {
        val perf = TurnPerf { testScheduler.currentTime }
        val drive = drive(perf = perf)
        val body = ByteChannel(autoFlush = true)
        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            TearAwareEvents(provider(), {}).run(drive, body, ZeroEventCapture(), ClientFrameEmitted { true }).toList()
        }
        try {
            delay(5_000)
            body.writeStringUtf8("data: {\"type\":\"ping\"}\n\n")
            runCurrent()
            delay(2_500)
            body.writeStringUtf8(
                "data: {\"type\":\"content_block_delta\",\"delta\":" +
                    "{\"type\":\"thinking_delta\",\"thinking\":\"synthetic\"}}\n\n",
            )
            runCurrent()
            delay(500)
            body.writeStringUtf8("data: {\"type\":\"ping\"}\n\n")
            body.close()
            assertEquals(3, reader.await().size)
            val snapshot = perf.snapshot()
            assertEquals(2_500L, snapshot.counters[PerfKeys.UP_GAP_MAX_MS])
            assertEquals(1L, snapshot.counters[PerfKeys.UP_GAPS_2S])
            assertEquals(UpstreamGapEnd.THINKING_DELTA, snapshot.upstreamGapEnd)
        } finally {
            body.cancel(null)
            reader.cancel()
            drive.slot.release()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `buffered upstream events do not charge downstream processing as provider silence`() = runTest {
        val perf = TurnPerf { testScheduler.currentTime }
        val drive = drive(perf = perf)
        val text = "data: {\"type\":\"ping\"}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\"}}\n\n"
        val body = ByteReadChannel(text.toByteArray())
        var observed = 0
        try {
            val events = TearAwareEvents(provider(), {}).run(
                drive,
                body,
                ZeroEventCapture(),
                ClientFrameEmitted { true },
            ).onEach { if (observed++ == 0) delay(2_500) }.toList()
            assertEquals(2, events.size)
            assertEquals(0L, perf.snapshot().counters[PerfKeys.UP_GAP_MAX_MS])
            assertEquals(0L, perf.snapshot().counters[PerfKeys.UP_GAPS_2S])
            assertEquals(2_500L, perf.snapshot().counters[PerfKeys.UP_BLOCKED_MAX_MS])
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `continuation POST wait reaches upstream timing without prior round processing`() = runBlocking {
        var now = 0L
        var waitMs = 2_500L
        val perf = TurnPerf { now }
        val timedProvider = object : Provider by provider() {
            override fun extraHeaders(creds: Credentials): Map<String, String> {
                now += waitMs
                return emptyMap()
            }
        }
        val upstream = UpstreamClient(totalTimeoutMs = 30_000, client = client)
        val post = SseRoundPost(
            timedProvider,
            upstream,
            UsageStore(tmp.resolve("continuation-usage.json"), tmp.resolve("continuation-rate.json")),
            TurnQuota(null, emptyMap(), null),
            SseRoundConsume(
                timedProvider,
                ZeroEventFailure(timedProvider, {}),
                TurnTelemetry("synthetic", PerfStats(tmp.resolve("continuation-perf.jsonl")), {}, ElapsedClock { now }),
                TearAwareEvents(timedProvider, {}),
            ),
            RetryNotice {},
        )
        val drive = drive(
            perf = perf,
            emitter = object : TurnTerminal by NoopTerminal() {
                override suspend fun ensureStarted() {
                    now += 10_000 // Slow downstream opening must not look like upstream silence.
                }
            },
        )
        val inputs = WsRoundInputs(
            drive,
            RoundBody.Text("""{"instructions":"SCENARIO:hello"}"""),
            RecordingSink2(),
            this,
            Job(),
            ClientFrameEmitted { true },
            0,
        )
        try {
            post.post(inputs)
            now += 10_000 // Outside either POST: must not be charged to the next round's upstream wait.
            waitMs = 3_500
            post.post(inputs)
            assertEquals(3_500L, perf.snapshot().counters[PerfKeys.UP_GAP_MAX_MS])
            assertEquals(2L, perf.snapshot().counters[PerfKeys.UP_GAPS_2S])
            assertEquals(10_000L, perf.snapshot().counters[PerfKeys.UP_BLOCKED_MAX_MS])
        } finally {
            inputs.turnJob.cancel()
            drive.slot.release()
        }
    }

    @Test
    fun `a pre-content reissue does not count the first attempt or backoff as upstream silence`() = runBlocking {
        var now = 0L
        var consumes = 0
        val perf = TurnPerf { now }
        val base = provider()
        val timedProvider = object : Provider by base {
            override fun extraHeaders(creds: Credentials): Map<String, String> {
                now += 1_000
                return emptyMap()
            }

            override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator {
                val normal = base.streamTranslator(meta, signals)
                return StreamTranslator { events, sink ->
                    if (++consumes == 1) {
                        events.first()
                        throw SocketException("synthetic pre-content reset")
                    }
                    normal.driveTurn(events, sink)
                }
            }
        }
        val upstream = UpstreamClient(
            totalTimeoutMs = 30_000,
            client = client,
            pacing = RetryPacing(backoff = RetryBackoff { _, _ -> now += 200 }),
            clock = ElapsedClock { now },
        )
        val post = SseRoundPost(
            timedProvider,
            upstream,
            UsageStore(tmp.resolve("reissue-usage.json"), tmp.resolve("reissue-rate.json")),
            TurnQuota(null, emptyMap(), null),
            SseRoundConsume(
                timedProvider,
                ZeroEventFailure(timedProvider, {}),
                TurnTelemetry("synthetic", PerfStats(tmp.resolve("reissue-perf.jsonl")), {}, ElapsedClock { now }),
                TearAwareEvents(timedProvider, {}),
            ),
            RetryNotice {},
        )
        val drive = drive(perf = perf)
        val inputs = WsRoundInputs(
            drive,
            RoundBody.Text("""{"instructions":"SCENARIO:hello"}"""),
            RecordingSink2(),
            this,
            Job(),
            ClientFrameEmitted { false },
            0,
        )
        try {
            assertTrue((post.post(inputs) as RoundResult.Outcome).outcome is TurnOutcome.Success)
            assertEquals(2, consumes)
            // Section 8 sends a stream that carried no event fresh at once: no wait is taken before the reissue.
            assertNull(perf.snapshot().counters[PerfKeys.BACKOFF_MS])
            assertEquals(1_000L, perf.snapshot().counters[PerfKeys.UP_GAP_MAX_MS])
            assertEquals(0L, perf.snapshot().counters[PerfKeys.UP_GAPS_2S])
        } finally {
            inputs.turnJob.cancel()
            drive.slot.release()
        }
    }

    // The whole path on the production transport and the real Responses translator: each round meets a refused reply
    // first, whose retry needs the connection that reply rode on, then streams and stops at the completed response.
    // Three such rounds make one connection. The upstream holds the stream's end back until the translator has closed
    // the client's blocks, which is when it stops. This is the end-to-end check; it does not fail without the drain
    // when the transport's own thread wins the race to the stream's end, which is why the arrangements that cannot
    // be won, `a round the upstream finished reads ...` here and the held-tail posts in UpstreamTailDrainTest, hold
    // the oracle.
    @Test
    fun `refused then retried rounds that stop at the completed response keep one connection`() = runBlocking {
        val stream = client.post("${mock.baseUrl}/v1/responses") {
            setBody("""{"instructions":"SCENARIO:hello"}""")
        }.bodyAsText()
        HeldTailUpstream(stream).use { upstream ->
            val post = postOver(upstream)
            val rounds = 3
            val outcomes = List(rounds) {
                val drive = drive()
                val recording = RecordingSink2()
                val sink = object : WireSink by recording {
                    override suspend fun closeAll() {
                        upstream.releaseTail()
                        recording.closeAll()
                    }
                }
                val inputs = WsRoundInputs(
                    drive,
                    RoundBody.Text("""{"instructions":"SCENARIO:hello"}"""),
                    sink,
                    this,
                    Job(),
                    ClientFrameEmitted { true },
                    0,
                )
                try {
                    (post.post(inputs) as RoundResult.Outcome).outcome
                } finally {
                    inputs.turnJob.cancel()
                    drive.slot.release()
                }
            }

            assertTrue(outcomes.all { it is TurnOutcome.Success }, "every round completed: $outcomes")
            assertEquals(2 * rounds, upstream.requests.get(), "each round was refused once and sent again")
            assertEquals(1, upstream.accepted.get(), "the retries and the rounds after them rode one connection")
        }
    }

    /** The production post and consume of a Responses head, on the production transport, aimed at [upstream]. */
    private fun postOver(upstream: HeldTailUpstream): SseRoundPost {
        val timed = provider(upstream.baseUrl)
        return SseRoundPost(
            timed,
            UpstreamClient(
                totalTimeoutMs = 30_000,
                client = UpstreamTransport().defaultClient(30_000),
                pacing = RetryPacing(backoff = RetryBackoff { _, _ -> }),
            ),
            UsageStore(tmp.resolve("held-usage.json"), tmp.resolve("held-rate.json")),
            TurnQuota(null, emptyMap(), null),
            SseRoundConsume(
                timed,
                ZeroEventFailure(timed, {}),
                TurnTelemetry("synthetic", PerfStats(tmp.resolve("held-perf.jsonl")), {}, ElapsedClock { 0L }),
                TearAwareEvents(timed, {}),
            ),
            RetryNotice {},
        )
    }

    /** A body that sends its [tail] and closes only when something waits on it for more, which a reader that stopped at
     *  the stream's last word does only if it goes on to read the stream's end. Nothing sent before that is read. */
    @OptIn(InternalAPI::class)
    private class TailOnDemand(private val body: ByteChannel, private val tail: String) : ByteReadChannel by body {
        override suspend fun awaitContent(min: Int): Boolean {
            if (!body.isClosedForWrite) {
                body.writeStringUtf8(tail)
                body.close()
            }
            return body.awaitContent(min)
        }
    }

    /** One consume over a stream that holds one event and a tail behind it, whose translator stops at that event and
     *  ends as [ended] says. Whether the tail was read is whether the body then stands at its end. */
    private fun streamLeftBehind(ended: TurnOutcome): Boolean = runBlocking {
        val base = provider()
        val translator = StreamTranslator { events, _ ->
            val _ = events.first()
            ended
        }
        val stopping = object : Provider by base {
            override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator = translator
        }
        val consume = SseRoundConsume(
            stopping,
            ZeroEventFailure(stopping, log = {}),
            TurnTelemetry("codex", PerfStats(tmp.resolve("left-perf.jsonl")), log = {}, clock = ElapsedClock { 0L }),
            TearAwareEvents(stopping, log = {}),
        )
        val body = ByteChannel(autoFlush = true)
        body.writeStringUtf8("data: {\"type\":\"response.completed\"}\n\n")
        val saved = client.post("${mock.baseUrl}/v1/responses") { setBody("""{"instructions":"SCENARIO:hello"}""") }
        val drive = drive()
        val inputs = WsRoundInputs(
            drive = drive,
            body = RoundBody.Text("{}"),
            sink = RecordingSink2(),
            scope = this,
            turnJob = Job(),
            frameEmittedThisRound = ClientFrameEmitted { true },
            eventsBase = 0,
        )
        try {
            val response = UpstreamResponse(saved, BodyChannelSource { TailOnDemand(body, "data: [DONE]\n\n") })
            val _ = consume.read(inputs, response)
            body.isClosedForRead
        } finally {
            inputs.turnJob.cancel()
            drive.slot.release()
        }
    }

    @Test
    fun `a round the upstream finished reads what its stream left behind`() {
        val finished = TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(reported = emptySet()))

        assertTrue(streamLeftBehind(finished), "the stream's end was read, so its connection can be reused")
    }

    @Test
    fun `a round the client abandoned leaves its stream alone`() {
        assertFalse(streamLeftBehind(TurnOutcome.ClientAbandoned()), "a stream ended on purpose is not read on")
    }

    @Test
    fun `each timing flow collection owns its reader stopwatch`() = runBlocking {
        var now = 0L
        val perf = TurnPerf { now }
        val events = flow {
            emit(buildJsonObject { })
            now += 41
            emit(buildJsonObject { })
        }
        val timed = UpstreamEventTiming(perf, null).observe(events)
        timed.toList()
        now += 10_000
        timed.toList()
        assertEquals(41L, perf.snapshot().counters[PerfKeys.UP_GAP_MAX_MS])
        assertEquals(0L, perf.snapshot().counters[PerfKeys.UP_GAPS_2S])
        assertEquals(0L, perf.snapshot().counters[PerfKeys.UP_BLOCKED_MAX_MS])
    }

    @Test
    fun `a reissued attempt re-baselines the zero-event count`() = runBlocking {
        val provider = provider()
        val consume = SseRoundConsume(
            provider,
            ZeroEventFailure(provider, log = {}),
            TurnTelemetry("codex", PerfStats(tmp.resolve("perf-dr90.jsonl")), log = {}, clock = ElapsedClock { 0L }),
            TearAwareEvents(provider, log = {}),
        )
        val drive = drive()
        val inputs = WsRoundInputs(
            drive = drive,
            body = RoundBody.Text("{}"),
            sink = RecordingSink2(),
            scope = this,
            turnJob = Job(),
            frameEmittedThisRound = ClientFrameEmitted { false },
            eventsBase = 0,
        )
        try {
            // Attempt 1: events flow, then the stream ends without a terminal — the shape that
            // precedes a G5 reissue. Its outcome is not under test; its EVENTS_IN pollution is.
            val first = consume.read(inputs, fetch("truncated"))
            assertTrue(drive.perfCounter(PerfKeys.EVENTS_IN) > 0, "attempt 1 must have parsed events")
            // Attempt 2 (the reissue, same inputs per the UpstreamClient contract): an HTML login
            // page with ZERO events. Pre-fix the round-scoped baseline counted attempt 1's events
            // and the zero-event diagnosis was skipped — an undiagnosable generic error instead.
            // Failure-handling slice 3: that diagnosis is Torn(NO_EVENTS) for a body that is no JSON error
            // (final-design.md section 7), where G2 read a login cause from the page's words.
            val second = consume.read(inputs, fetch("zero_event_auth"))
            val failure = second as TurnOutcome.Failure
            assertEquals(
                Tear.NO_EVENTS,
                (failure.cause as? FailureCause.Path.Torn)?.why,
                "zero-event body must be diagnosed: $failure",
            )
            // Control: attempt 1's verdict must stay untouched — its round had real events.
            assertFalse(first is TurnOutcome.Failure && (first.cause as? FailureCause.Path.Torn)?.why == Tear.NO_EVENTS)
        } finally {
            inputs.turnJob.cancel()
            drive.slot.release()
        }
    }
}

/** An upstream that refuses every other request with a small 503 and answers the rest with [stream], whole but for its
 *  end: the chunked terminator is held until [releaseTail]. A thread per connection, so a second connection is accepted
 *  at once and [accepted] counts every one a client dials. */
private class HeldTailUpstream(private val stream: String) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val connections = CopyOnWriteArrayList<Socket>()
    private val tail = Semaphore(0)

    val accepted = AtomicInteger()
    val requests = AtomicInteger()
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        val _ = thread(isDaemon = true, name = "held-tail-upstream") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                connections += socket
                val _ = accepted.incrementAndGet()
                val _ = thread(isDaemon = true) { val _ = runCatching { socket.use(::serveEach) } }
            }
        }
    }

    fun releaseTail() = tail.release()

    private fun serveEach(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream()
        while (true) {
            val length = contentLength(input) ?: return
            val _ = input.readNBytes(length)
            if (requests.incrementAndGet() % 2 == 1) {
                output.write(REFUSAL)
            } else {
                // A permit left by an earlier round's second close of its blocks is not this stream's release.
                tail.drainPermits()
                val body = stream.toByteArray()
                output.write(STREAM_HEAD + "${body.size.toString(16)}\r\n".toByteArray() + body + "\r\n".toByteArray())
                output.flush()
                tail.acquire()
                output.write(STREAM_END)
            }
            output.flush()
        }
    }

    private fun contentLength(input: InputStream): Int? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next == -1) return null
            head.append(next.toChar())
        }
        val header = head.lineSequence().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
        return header?.substringAfter(':')?.trim()?.toInt() ?: 0
    }

    override fun close() {
        server.close()
        connections.forEach { val _ = runCatching { it.close() } }
    }

    private companion object {
        const val REFUSAL_BODY = """{"error":{"message":"overloaded"}}"""
        val REFUSAL = (
            "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${REFUSAL_BODY.length}\r\n\r\n$REFUSAL_BODY"
            ).toByteArray()
        val STREAM_HEAD = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
            .toByteArray()
        val STREAM_END = "0\r\n\r\n".toByteArray()
    }
}
