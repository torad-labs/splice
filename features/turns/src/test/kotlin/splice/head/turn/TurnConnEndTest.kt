// NEW (V4-79): the oversized-streaming-event ending, pinned on what it hands the emitter.
//
// SseFrameTooLargeException is the one connection-class failure that can land on either side of
// content: an upstream event can blow the frame cap before anything has been read, or after the
// client has already rendered half an answer. V4-79 read that fork here; V4-81 removed the hand
// copy and moved it to SseEmitter.emitError, so what this file now pins is the arm's own two
// facts — the failure is TRANSIENT (a decision, argued in the cell) and the surface no longer
// branches on content at all.
//
// The assertions are on what the EMITTER received, not on a splice-side count: the only fact that
// matters is the type that reaches Claude Code, because 2.1.257 retries an in-band
// overloaded_error and ends the session on an in-band api_error. Since failure-handling slice 2 the
// words are the line the real emitter's turn voice says for that cause, and the transport's own
// detail goes to the log; the trace records the line the client read.
//
// MUTATION PROOF (recorded in the ledger): flip `permanent = false` to `true` at TurnConnEnd.kt and
// the transient cell goes red BY NAME; re-introduce a content fork here and the content-blind cell
// goes red — so each pins one thing this file still owns.
//
// The rig mirrors TurnEndingAccountingTest's (same package, same provider/telemetry/drive idiom);
// it is rebuilt here rather than shared because these cells need a RECORDING terminal where that
// file needs a dead-client one, and its Rig is private to it.
package splice.head.turn

import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.Tear
import splice.core.failure.Unreach
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.storage.ActivityDays
import splice.core.turn.CodeModeDivergenceMarker
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadHealthCounters
import splice.head.TestResponsesProvider
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.testVoice
import splice.head.usage.OutputClamp
import splice.head.wire.ClientChannel
import splice.head.wire.ClientInbound
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.SseEmitter
import splice.head.wire.SseEmitterFactory
import splice.head.wire.StreamWiring
import splice.head.wire.TurnTerminal
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.failure.FailureSource
import splice.upstream.failure.UpstreamFailureClassifier
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.SseFrameTooLarge
import splice.upstream.transport.StreamTornBeforeClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private const val TRACE_DAY_EPOCH_MS = 1_789_725_600_000L

/** Holds a credential so provider construction is honest; the surface under test never dials out. */
private class ConnEndFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials() = Credentials.Bearer("tok", "acct")
    override suspend fun refresh() = credentials()
    override suspend fun describe() = AuthDescription(true, "fake")
}

/** The real emitter, recording what it was handed: the cause's own TYPE and whether a re-send repeats it. The words
 *  the client reads are in [frames], as the emitter wrote them. */
private class ConnEndRecordingTerminal(private val wire: SseEmitter, val frames: List<String>) : TurnTerminal by wire {
    var errorType: ErrorType? = null
    var errorPermanent: Boolean = false

    override suspend fun emitError(cause: FailureCause) {
        errorType = ClientWire.ownType(cause)
        errorPermanent = cause.permanent || cause.deterministic
        wire.emitError(cause)
    }

    /** The words of the one error frame the client received. */
    val errorMessage: String
        get() {
            val frame = frames.single { it.startsWith("event: error") }
            val error = Json.parseToJsonElement(frame.substringAfter("data: ").trim()).jsonObject.getValue("error")
            return error.jsonObject.getValue("message").jsonPrimitive.content
        }
}

/** The line a turn with no content on the wire yet ends with for [cause]. */
private fun lineBeforeContent(cause: FailureCause) = testVoice.ending(cause, Position(Moment.COMMITTED, false))

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnConnEndTest {

    private lateinit var tmp: Path

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        tmp = tempDir
    }

    private fun provider(): Provider = TestResponsesProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = "codex", label = "claudex"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = ConnEndFakeAuth(),
            locations = ProviderLocations(baseUrl = "http://127.0.0.1:1"),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
    )

    /** One TurnConnEnd plus the drive it is handed; [tag] isolates each case's perf file. */
    private inner class Rig(private val tag: String, private val traceEnabled: Boolean = false) {
        val logged = CopyOnWriteArrayList<String>()
        val log = LogSink { logged.add(it) }
        val health = HeadHealthCounters()
        val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
        val traceDir: Path = tmp.resolve("trace-$tag")
        val telemetry = TurnTelemetry("codex", PerfStats(perfFile), log, ElapsedClock { 5L })
        lateinit var emitter: ConnEndRecordingTerminal
        val connEnd: TurnConnEnd
        init {
            val p = provider()
            connEnd = TurnConnEnd(p, log, telemetry, TurnFailures(p), health)
        }

        /** The real emitter for a turn whose content counter is [perf]'s, as the streaming path builds it. */
        private fun emitterFor(perf: TurnPerf): ConnEndRecordingTerminal {
            val frames = CopyOnWriteArrayList<String>()
            val wire = SseEmitterFactory().create(
                write = { frames.add(it) },
                model = "claude-codex--gpt-5.6-sol",
                usagePayload = { buildJsonObject { } },
                streaming = StreamWiring(
                    contentReached = { (perf.snapshot().counters[PerfKeys.CONTENT_FRAMES_OUT] ?: 0L) > 0 },
                ),
                voice = testVoice,
            )
            return ConnEndRecordingTerminal(wire, frames)
        }

        /** The turn record the trace wrote, once the async writer has drained. */
        fun turnRecord() = run {
            assertEquals(true, AsyncFileIo.drain())
            Json.parseToJsonElement(Files.readAllLines(traceDir.resolve("codex-2026-09-18.jsonl")).last()).jsonObject
        }

        suspend fun drive(): TurnDrive {
            val perf = TurnPerf()
            emitter = emitterFor(perf)
            val meta = TurnMeta(
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
            )
            return TurnDrive(
                inputs = TurnInputs(
                    built = BuiltTurn(
                        requestBody = buildJsonObject { },
                        meta = meta,
                        extraHeaders = emptyMap(),
                        toolSearch = null,
                    ),
                    slot = TurnSlot(InflightGate(LiveLimit { 1 }).admittedSlot()) {},
                    t0 = 0,
                    perf = perf,
                    trace = if (traceEnabled) newTrace(meta) else null,
                    voice = testVoice,
                ),
                emitter = emitter,
                watchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
                pipeline = TurnPipeline(
                    CompactStats(perfFile.resolveSibling("compact-$tag.jsonl")),
                    log = log,
                    clampOutput = OutputClamp { it },
                ),
                signals = RunnerSignals(),
                channel = ClientChannel(
                    ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = {}),
                    Mutex(),
                    AtomicBoolean(false),
                ),
            )
        }

        private fun newTrace(meta: TurnMeta) = splice.head.syntheticTraceStore(
            ActivityDays(
                traceDir,
                "codex",
                retentionDays = 7,
                clock = WallClock { TRACE_DAY_EPOCH_MS },
                ownerOnly = true,
            ),
            head = "codex",
            maxBodyChars = 4096,
            now = WallClock { TRACE_DAY_EPOCH_MS },
        ).begin(meta, ClientInbound("POST", "/v1/messages", emptyMap(), "synthetic request"))
    }

    /** Drives the oversized-event arm with [contentFrames] frames already counted for the turn. */
    private fun emitOversized(tag: String, contentFrames: Long): ConnEndRecordingTerminal =
        emitWith(tag, contentFrames) { rig, drive -> rig.connEnd.emitOversized(drive, SseFrameTooLarge("data", 1)) }

    private fun emitWith(
        tag: String,
        contentFrames: Long,
        emit: suspend (Rig, TurnDrive) -> Unit,
    ): ConnEndRecordingTerminal {
        val rig = Rig(tag)
        return runBlocking {
            val drive = rig.drive()
            try {
                // The SAME counter production reads: ClientChannel adds to it per content frame.
                drive.perf.add(PerfKeys.CONTENT_FRAMES_OUT, contentFrames)
                emit(rig, drive)
            } finally {
                drive.slot.release()
                AsyncFileIo.drain() // perf rows append asynchronously; drain before the dir is swept
            }
            rig.emitter
        }
    }

    @Test
    fun `an oversized event reaches the emitter as a TRANSIENT api_error`() = runBlocking {
        val emitter = emitOversized("pre-content", contentFrames = 0)

        assertEquals(
            ErrorType.API_ERROR,
            emitter.errorType,
            "this surface reports the failure; the wire type is decided at the emitter",
        )
        // THE ONE DECISION THIS ARM STILL OWNS, and the row asked for it explicitly: an oversized
        // frame is TRANSIENT. The frame size is a property of the RESPONSE the upstream host chose
        // to send, not of the request we sent, so a re-send buys a genuinely different response and
        // can come back small. Contrast TurnEnding's unparseable base_url, which is a property of
        // our own config and reproduces exactly — that one is permanent. Without this flag the
        // emitter would have to guess, and guessing "permanent" here would end sessions that a
        // retry fixes.
        assertEquals(false, emitter.errorPermanent, "an oversized upstream frame is a transient condition")
        assertEquals(
            lineBeforeContent(FailureCause.Path.Torn(Tear.OVERSIZED_EVENT, toolOpen = false)).text,
            emitter.errorMessage,
        )
    }

    @Test
    fun `an oversized event's trace records the line the client read, and its size stays in the log`() = runBlocking {
        val rig = Rig("oversized-trace", traceEnabled = true)
        val drive = rig.drive()
        try {
            rig.connEnd.emitOversized(drive, SseFrameTooLarge("data", 1, observed = 70_000))
        } finally {
            drive.slot.release()
        }
        val said = rig.emitter.errorMessage
        assertEquals(said.removePrefix("[splice] "), rig.turnRecord()["failure_sentence"]?.jsonPrimitive?.content)
        assertTrue(rig.logged.any { "frame reached 70000 characters" in it }, "${rig.logged}")
        assertTrue("70000" !in said, said)
    }

    // V4-164, the operator's banner verbatim: "bonsai: upstream connection failed (no detail) —
    // retry". The JDK client's refused connect is a ConnectException with a NULL message, and this
    // surface printed Throwable.message. Since failure-handling slice 2 the endpoint and the reason go to
    // the log, and the client reads the refused-connection line, which names no host.
    @Test
    fun `a refused connect logs the endpoint and the reason, and the client reads its line`() = runBlocking {
        // The JDK client's refusal (V4-167, measured): a ConnectException over a ClosedChannelException.
        val refused = java.net.ConnectException().apply { initCause(java.nio.channels.ClosedChannelException()) }
        val rig = Rig("refused")
        val drive = rig.drive()
        try {
            assertEquals(true, rig.connEnd.tryEmit(drive, refused), "TurnConnEnd owns this failure class")
        } finally {
            drive.slot.release()
            AsyncFileIo.drain()
        }
        val emitter = rig.emitter
        assertEquals(ErrorType.OVERLOADED, emitter.errorType, "a refused connect stays the class the client retries")
        val local = FailureCause.Path.Unreachable(Unreach.REFUSED, local = true)
        assertEquals(lineBeforeContent(local).text, emitter.errorMessage)
        assertTrue("127.0.0.1" !in emitter.errorMessage, emitter.errorMessage)
        val reason = "connection refused by 127.0.0.1:1: nothing is listening there; " +
            "the server is down or still starting"
        assertTrue(rig.logged.any { reason in it }, "the log keeps the endpoint and the reason: ${rig.logged}")
    }

    @Test
    fun `a refused local connect retains its cause and runtime port instead of a generic reset`() = runBlocking {
        val rig = Rig("refused-port")
        val drive = rig.drive()
        try {
            val error = java.net.ConnectException().apply { initCause(java.nio.channels.ClosedChannelException()) }
            assertEquals(true, rig.connEnd.tryEmit(drive, error))
        } finally {
            drive.slot.release()
            assertEquals(true, AsyncFileIo.drain())
        }
        val row = Json.parseToJsonElement(Files.readAllLines(rig.perfFile).last()).jsonObject
        assertEquals("CONNECT_REFUSED", row["cause"]?.jsonPrimitive?.content)
        assertEquals("1", row["refused_runtime_port"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a genuine reset and a DNS connect failure never acquire a refused runtime port`() = runBlocking {
        val failures = listOf(
            java.net.SocketException("synthetic reset"),
            java.net.ConnectException().apply { initCause(java.nio.channels.UnresolvedAddressException()) },
        )
        failures.forEachIndexed { index, failure ->
            val rig = Rig("not-refused-$index")
            val drive = rig.drive()
            try {
                assertEquals(true, rig.connEnd.tryEmit(drive, failure))
            } finally {
                drive.slot.release()
                assertEquals(true, AsyncFileIo.drain())
            }
            val row = Json.parseToJsonElement(Files.readAllLines(rig.perfFile).last()).jsonObject
            assertEquals(null, row["refused_runtime_port"])
            assertEquals(null, row["cause"])
        }
    }

    @Test
    fun `a divergent upstream tear remains countable on its connection-reset trace`() = runBlocking {
        val rig = Rig("diverged-tear", traceEnabled = true)
        val drive = rig.drive()
        try {
            val cause = java.io.IOException("upstream closed").apply { addSuppressed(CodeModeDivergenceMarker()) }
            rig.connEnd.emitTorn(drive, StreamTornBeforeClient(cause))
        } finally {
            drive.slot.release()
            assertEquals(true, AsyncFileIo.drain())
        }
        val row = Json.parseToJsonElement(Files.readAllLines(rig.perfFile).last()).jsonObject
        assertEquals("1", row.getValue(PerfKeys.CODE_MODE_DIVERGENCE).jsonPrimitive.content)
        val day = rig.traceDir.resolve("codex-2026-09-18.jsonl")
        val turn = Json.parseToJsonElement(Files.readAllLines(day).last()).jsonObject
        val divergence = turn.getValue("perf").jsonObject.getValue("counters").jsonObject
            .getValue(PerfKeys.CODE_MODE_DIVERGENCE).jsonPrimitive.content
        assertEquals("1", divergence)
    }

    // The local-hold cases went with the hold itself (failure-handling slice 1): every ending that reaches here is
    // the provider's own answer.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("false,1", "true,0")
    fun `final rate accounting excludes plan holds and absorbed diagnostic events`(
        plan: Boolean,
        providerTurns: Long,
    ) = runBlocking {
        val rig = Rig("rate-$plan")
        val drive = rig.drive()
        repeat(3) { rig.health.provider() }
        repeat(5) { rig.health.local() }
        val p = provider()
        val ending = TurnKnownEnd(p, rig.log, rig.telemetry, rig.health)
        val body = """{"error":{"type":"rate_limit_error","message":"synthetic rate limit"}}"""
        val read = UpstreamFailureClassifier.classify(FailureSource.HTTP, body, 429)
        val failure = splice.upstream.transport.UpstreamFailed(
            read.cause,
            FailureEvidence(read.message, 429, body = body),
            planLimit = if (plan) splice.core.usage.PlanLimit("five_hour", 1000) else null,
        )
        try {
            ending.emitFailed(drive, failure)
            assertEquals(providerTurns, rig.health.rateLimitSnapshot().providerTurns)
            assertEquals(0L, rig.health.rateLimitSnapshot().heldTurns)
        } finally {
            drive.slot.release()
            assertEquals(true, AsyncFileIo.drain())
        }
    }

    /** The trace says what the client read (failure-handling slice 2), though its perf row is written before the
     *  error frame (DR-128); the transport's own account stays in the log. */
    @Test
    fun `a conn reset trace records the line the client read, and the log keeps the transport failure`() =
        runBlocking {
            val rig = Rig("failure-sentence", traceEnabled = true)
            val drive = rig.drive()
            try {
                rig.connEnd.emitTorn(drive, StreamTornBeforeClient(ClosedWriteChannelException()))
            } finally {
                drive.slot.release()
            }
            val turn = rig.turnRecord()
            assertEquals("error:conn-reset", turn.getValue("outcome").jsonPrimitive.content)
            val said = rig.emitter.errorMessage
            val reset = FailureCause.Path.Unreachable(Unreach.RESET_BEFORE_REPLY, local = true)
            assertEquals(lineBeforeContent(reset).text, said)
            assertEquals(said.removePrefix("[splice] "), turn["failure_sentence"]?.jsonPrimitive?.content)
            assertTrue(rig.logged.any { "the connection to 127.0.0.1:1 closed mid-request" in it }, "${rig.logged}")
        }
}
