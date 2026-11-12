// NEW: V4-404 — a failed turn's trace has a sentence in words for EVERY failure, not only the ones whose connection
// ended. RED on the pre-fix telemetry: only TurnConnEnd wrote `failure_sentence`, so an upstream-failed turn's detail
// read `error:upstream-failed · 3 retries` and then Timing (Marlin's walk of V4-349 on bb54736ea).
//
// Rewritten by failure-handling slice 2 (2026-10-26): the sentence is the line the client read, without its
// "[splice] " prefix, so the trace cannot say one thing while the client read another. The walk is over every leaf of
// the sealed cause, through the real terminal of each path, so a leaf added later is walked the day it exists.
package splice.head.v4404

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ServerFault
import splice.core.failure.StoppedBy
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.perf.TurnPerf
import splice.core.storage.ActivityDays
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadDeps
import splice.head.HeadHealthCounters
import splice.head.TestResponsesProvider
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.headDeps
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.pipeline.everyLeaf
import splice.head.round.RunnerSignals
import splice.head.testVoice
import splice.head.turn.CancellationSeal
import splice.head.turn.HeadRestart
import splice.head.turn.TurnDrive
import splice.head.turn.TurnFinish
import splice.head.turn.TurnInputs
import splice.head.turn.TurnKnownEnd
import splice.head.turn.TurnSlot
import splice.head.turn.TurnTelemetry
import splice.head.turn.TurnUsageStamp
import splice.head.usage.OutputClamp
import splice.head.wire.ClientChannel
import splice.head.wire.ClientInbound
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.SseEmitterFactory
import splice.head.wire.StreamWiring
import splice.head.wire.TurnTerminal
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.Ticker
import splice.upstream.TurnSignals
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.UpstreamFailed
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TRACE_DAY_EPOCH_MS = 1_789_725_600_000L
private const val TRACE_FILE = "codex-2026-09-18.jsonl"

/** What a failure left for the log: a path, a class name and a count, none of which a client line may carry. */
private const val EVIDENCE = "code-mode runtime failed: IllegalStateException at SyntheticCell.kt:83; results=0"

/** After content: where the translators place every failure they build. */
private val MID = Position(Moment.MID_CONTENT, streamBegan = true)

/** Holds a credential so provider construction is honest; the surfaces under test never dial out. */
private class FakeAuth : RefreshableAuthProvider {
    override suspend fun credentials() = Credentials.Bearer("tok", "acct")
    override suspend fun refresh() = credentials()
    override suspend fun describe() = AuthDescription(true, "fake")
}

/** The `error` object of the one error frame among [frames], or null when none was written. */
private fun errorOf(frames: List<String>): JsonObject? =
    frames.singleOrNull { it.startsWith("event: error") }?.let { frame ->
        Json.parseToJsonElement(frame.substringAfter("data: ").trim()).jsonObject.getValue("error").jsonObject
    }

private fun JsonObject.words(): String? = this["message"]?.jsonPrimitive?.content

class OutcomeSentenceTest {

    /** One turn and the real terminal it ends through: the stream's emitter, or the collect path's body. */
    private inner class Rig(private val tag: String, private val tmp: Path, val stream: Boolean = true) {
        val log = LogSink { }
        val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
        val traceDir: Path = tmp.resolve("trace-$tag")
        val telemetry = TurnTelemetry("codex", PerfStats(perfFile), log, ElapsedClock { 5L })
        val meta = TurnMeta(
            compact = false,
            reasoning = TurnReasoning(
                showReasoning = ReasoningDisplay.TEXT,
                effort = "high",
                summary = "detailed",
                budgetTokens = null,
            ),
            route = TurnRoute(
                stream = stream,
                originalModel = "claude-codex--gpt-5.6-sol",
                upstreamModel = "gpt-5.6-sol",
                clientMaxTokens = 100,
            ),
        )
        val trace = splice.head.syntheticTraceStore(
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

        /** Every frame the stream's client was written. Once [socketClosed] is set, a write fails as a closed
         *  socket's does. */
        val frames = CopyOnWriteArrayList<String>()
        var socketClosed = false
        private val collected = CollectingTerminal(meta.route.originalModel, { buildJsonObject { } }, voice = testVoice)
        val terminal: TurnTerminal = if (stream) {
            SseEmitterFactory().create(
                write = { frame ->
                    if (socketClosed) throw IOException("synthetic server socket closed")
                    frames.add(frame)
                },
                model = meta.route.originalModel,
                usagePayload = { buildJsonObject { } },
                streaming = StreamWiring(contentReached = { false }),
                voice = testVoice,
            )
        } else {
            collected
        }

        /** Where this terminal stands for an ending before any content: in band on a committed stream, or the
         *  collected body. */
        val position = Position(if (stream) Moment.COMMITTED else Moment.NON_STREAM, streamBegan = false)

        /** The error the client read: the stream's error frame, or the collected body's. */
        fun clientError(): JsonObject? = if (stream) errorOf(frames) else collected.responseBody()["error"]?.jsonObject

        suspend fun drive(): TurnDrive = TurnDrive(
            inputs = TurnInputs(
                built = BuiltTurn(
                    requestBody = buildJsonObject { },
                    meta = meta,
                    extraHeaders = emptyMap(),
                    toolSearch = null,
                ),
                slot = TurnSlot(InflightGate(LiveLimit { 1 }).admittedSlot()) {},
                t0 = 0,
                perf = TurnPerf(),
                trace = trace,
                voice = testVoice,
            ),
            emitter = terminal,
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

        fun finish(deps: HeadDeps) = TurnFinish(
            ElapsedClock { 5L },
            log,
            TurnUsageStamp(deps.stores.usageStore, log, telemetry),
            HeadHealthCounters(),
            telemetry,
        )

        fun seal(deps: HeadDeps) = CancellationSeal(
            provider(),
            log,
            telemetry,
            HeadHealthCounters(),
            TurnUsageStamp(deps.stores.usageStore, log, telemetry),
        )

        /** The turn record the trace wrote, once the async writer has drained. */
        fun turnRecord() = run {
            assertTrue(AsyncFileIo.drain())
            Json.parseToJsonElement(Files.readAllLines(traceDir.resolve(TRACE_FILE)).last()).jsonObject
        }
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
            auth = FakeAuth(),
            locations = ProviderLocations(baseUrl = "http://127.0.0.1:1"),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
    )

    private fun sentenceOf(record: JsonObject): String? = record["failure_sentence"]?.jsonPrimitive?.content

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `every failure's turn record says the line its client read`(stream: Boolean, @TempDir tmp: Path) =
        runBlocking {
            val deps = headDeps(tmp.resolve("stores"))
            for ((at, cause) in everyLeaf.withIndex()) {
                val rig = Rig("leaf-$at", tmp, stream)
                val drive = rig.drive()
                try {
                    rig.finish(deps).finishTurn(drive, TurnOutcome.Failure(cause, MID, FailureEvidence(EVIDENCE)))
                } finally {
                    drive.slot.release()
                }
                val said = rig.clientError()?.words()
                assertEquals(testVoice.ending(cause, rig.position).text, said, "$cause: the line its client read")
                assertEquals(said?.removePrefix("[splice] "), sentenceOf(rig.turnRecord()), "$cause: its trace")
                assertTrue("SyntheticCell" !in said.orEmpty(), "$cause: the evidence reached the client: $said")
            }
        }

    @Test
    fun `a translator parsed provider invalid request retains provider origin`(@TempDir tmp: Path) = runBlocking {
        val rig = Rig("parsed-provider", tmp)
        val event = Json.parseToJsonElement(
            """{"type":"response.failed","response":{"error":{"code":"request_too_large",""" +
                """"message":"prompt is too long"}}}""",
        ).jsonObject
        val outcome = provider().streamTranslator(
            rig.meta,
            TurnSignals(watchdogFired = { null }, clientGone = { false }),
        ).driveTurn(flowOf(event), rig.terminal)
        val failure = outcome as? TurnOutcome.Failure ?: error("provider error must fail")
        assertTrue(failure.cause.providerReported)
        assertEquals(ErrorType.INVALID_REQUEST, ClientWire.ownType(failure.cause))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a progress timeout's trace says the line its ending is told with`(stream: Boolean, @TempDir tmp: Path) =
        runBlocking {
            val rig = Rig("progress-timeout", tmp, stream)
            var now = 0L
            val dog = TurnWatchdog(
                WatchdogBudget(10.seconds, 10.seconds, 600.milliseconds),
                clock = ElapsedClock { now },
                ticker = Ticker { interval ->
                    now += interval
                    true
                },
            )
            val target = Job()
            val cap = dog.launchTotalCap(this, target)
            withTimeout(1_000) { target.join() }
            cap.cancel()
            val drive = rig.drive().copy(watchdog = dog)
            try {
                rig.seal(headDeps(tmp.resolve("stores"))).seal(drive, stream, CancellationException("synthetic"))
            } finally {
                drive.slot.release()
            }
            val spent = FailureCause.Splice.TurnBudgetSpent(budgetMs = 600L, beforeFirstAttempt = false)
            val line = testVoice.ending(spent, rig.position)
            assertEquals(line.sentence, sentenceOf(rig.turnRecord()))
            if (stream) {
                val error = checkNotNull(rig.clientError()) { "the connected client is owed an error frame" }
                assertEquals(line.text, error.words())
                assertEquals(ErrorType.OVERLOADED.wireName, error["type"]?.jsonPrimitive?.content)
            } else {
                // Collect has no half-open 200 to rescue: the seal records the ending and writes no body.
                assertEquals(null, rig.terminal.lineWritten)
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a shutdown cut stays splice's own failure, and its trace says the restart's line after its socket closed`(
        stream: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig("restart-$stream", tmp, stream)
        rig.socketClosed = true
        val drive = rig.drive()
        drive.channel.clientGone.set(true)
        try {
            rig.seal(headDeps(tmp.resolve("stores"))).seal(drive, stream, HeadRestart())
        } finally {
            drive.slot.release()
        }
        val record = rig.turnRecord()
        assertEquals(OutcomeTag.RESTARTED.wire, record.getValue("outcome").jsonPrimitive.content)
        val restart = testVoice.ending(FailureCause.Splice.Stopped(StoppedBy.RESTART), rig.position)
        assertEquals(restart.sentence, sentenceOf(record))
        assertEquals(null, rig.terminal.lineWritten, "no frame reached the closed socket")
        assertTrue(OutcomeTags.isFailed(OutcomeTag.RESTARTED.wire))
        assertTrue(!OutcomeTags.isStopped(OutcomeTag.RESTARTED.wire))
    }

    @Test
    fun `an upstream-failed turn after three retries reads in words, Marlin's walk`(@TempDir tmp: Path) = runBlocking {
        val rig = Rig("upstream-failed", tmp)
        val knownEnd = TurnKnownEnd(provider(), rig.log, rig.telemetry, HeadHealthCounters())
        val drive = rig.drive()
        try {
            val body = """{"error":{"message":"boom"}}"""
            val cause = FailureCause.Provider.ServerError(ServerFault.INTERNAL)
            knownEnd.emitFailed(drive, UpstreamFailed(cause, FailureEvidence("boom", 502, body = body), layers = 3))
        } finally {
            drive.slot.release()
        }
        val record = rig.turnRecord()
        assertEquals("error:upstream-failed", record.getValue("outcome").jsonPrimitive.content)
        val said = checkNotNull(rig.clientError()?.words()) { "the upstream-failed turn's client read no line" }
        assertEquals(said.removePrefix("[splice] "), sentenceOf(record), "the trace says the line the client read")
    }
}
