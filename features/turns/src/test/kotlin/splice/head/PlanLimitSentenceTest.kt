// NEW: the line a plan-limit ending speaks: the reset the provider named, in the zone its head speaks in. It is the
// same line for the client and the trace, so the two cannot name different instants or name one differently.
// Rewritten by failure-handling slice 2 (2026-10-26): the line is the turn voice's, and the trace keeps its sentence
// (final-design.md section 10, "trace equals client"). The ending runs through TurnKnownEnd and the real emitter.
package splice.head

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.HeadVoice
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.RequestFacts
import splice.core.failure.TurnVoice
import splice.core.failure.VoiceFacts
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.WatchdogBudget
import splice.core.usage.PlanLimit
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LocalTimeText
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.turn.TurnDrive
import splice.head.turn.TurnInputs
import splice.head.turn.TurnKnownEnd
import splice.head.turn.TurnSlot
import splice.head.turn.TurnTelemetry
import splice.head.usage.OutputClamp
import splice.head.wire.ClientChannel
import splice.head.wire.ClientInbound
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.SseEmitterFactory
import splice.head.wire.StreamWiring
import splice.head.wire.TurnTrace
import splice.upstream.BuiltTurn
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.UpstreamFailed
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private val RESET = Instant.parse("2026-10-05T00:00:00Z")
private const val TOKYO = "Asia/Tokyo"
private const val TRACE_DAY_EPOCH_MS = 1_789_725_600_000L
private const val TRACE_FILE = "codex-2026-09-18.jsonl"
private const val TOO_MANY_REQUESTS = 429

private class PlanLimitAuth : RefreshableAuthProvider {
    override suspend fun credentials() = Credentials.Bearer("tok", "acct")
    override suspend fun refresh() = credentials()
    override suspend fun describe() = AuthDescription(true, "fake")
}

class PlanLimitSentenceTest {

    private val meta = TurnMeta(
        compact = false,
        reasoning = TurnReasoning(ReasoningDisplay.TEXT, effort = "high", summary = "detailed", budgetTokens = null),
        route = TurnRoute(
            stream = true,
            originalModel = "claude-codex--gpt-5.6-sol",
            upstreamModel = "gpt-5.6-sol",
            clientMaxTokens = 100,
        ),
    )

    private val provider = TestResponsesProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = "codex", label = "claudex"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = PlanLimitAuth(),
            locations = ProviderLocations(baseUrl = "http://127.0.0.1:1"),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
    )

    @Test
    fun `a plan-limit ending's trace says the line its client read, the reset as the head's zone reads it`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val voice = HeadVoice("claudex", "claudex login", VoiceFacts(zone = ZoneId.of(TOKYO)))
            .turn(RequestFacts(compaction = false, firstExchange = false))
        val log = LogSink { }
        val telemetry = TurnTelemetry("codex", PerfStats(tmp.resolve("perf.jsonl")), log, ElapsedClock { 5L })
        val traceDir = tmp.resolve("trace")
        val trace = syntheticTraceStore(
            ActivityDays(traceDir, "codex", 7, WallClock { TRACE_DAY_EPOCH_MS }, ownerOnly = true),
            head = "codex",
            maxBodyChars = 4096,
            now = WallClock { TRACE_DAY_EPOCH_MS },
        ).begin(meta, ClientInbound("POST", "/v1/messages", emptyMap(), "synthetic request"))
        val frames = CopyOnWriteArrayList<String>()
        val drive = streamingDrive(tmp, log, trace, frames, voice)
        val spent = UpstreamFailed(
            FailureCause.Provider.Limited(Lift.At(RESET, firm = true, LiftSource.RESET_HEADER), LimitWindow.WEEKLY),
            FailureEvidence("usage_limit_reached", TOO_MANY_REQUESTS),
            layers = 1,
            planLimit = PlanLimit("seven_day", RESET.epochSecond),
        )
        try {
            TurnKnownEnd(provider, log, telemetry, HeadHealthCounters()).emitFailed(drive, spent)
        } finally {
            drive.slot.release()
        }

        val frame = frames.single { it.startsWith("event: error") }
        val said = Json.parseToJsonElement(frame.substringAfter("data: ").trim()).jsonObject
            .getValue("error").jsonObject.getValue("message").jsonPrimitive.content
        val instant = LocalTimeText(ZoneId.of(TOKYO)).at(RESET.epochSecond)
        assertTrue(instant in said && "JST" in said, "the reset is named as Tokyo reads it, $instant: $said")
        assertTrue(AsyncFileIo.drain())
        val record = Json.parseToJsonElement(Files.readAllLines(traceDir.resolve(TRACE_FILE)).last()).jsonObject
        assertEquals("error:plan-limit", record.getValue("outcome").jsonPrimitive.content)
        assertEquals(said.removePrefix("[splice] "), record["failure_sentence"]?.jsonPrimitive?.content)
    }

    /** A streaming drive tracing into [trace], whose emitter speaks in [voice] and writes its frames to [frames]. */
    private suspend fun streamingDrive(
        tmp: Path,
        log: LogSink,
        trace: TurnTrace?,
        frames: MutableList<String>,
        voice: TurnVoice,
    ): TurnDrive {
        val emitter = SseEmitterFactory().create(
            write = { frames.add(it) },
            model = meta.route.originalModel,
            usagePayload = { buildJsonObject { } },
            streaming = StreamWiring(contentReached = { false }),
            voice = voice,
        )
        return TurnDrive(
            inputs = TurnInputs(
                built = BuiltTurn(buildJsonObject { }, meta, extraHeaders = emptyMap(), toolSearch = null),
                slot = TurnSlot(InflightGate(LiveLimit { 1 }).admittedSlot()) {},
                t0 = 0,
                perf = TurnPerf(),
                trace = trace,
                voice = voice,
            ),
            emitter = emitter,
            watchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
            pipeline = TurnPipeline(CompactStats(tmp.resolve("compact.jsonl")), log, OutputClamp { it }),
            signals = RunnerSignals(),
            channel = ClientChannel(
                ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = {}),
                Mutex(),
                AtomicBoolean(false),
            ),
        )
    }
}
