// The real shared SSE/WS instrumentation dates provider receipt, even without output.
package splice.head.turn

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.dialect.responses.ReasoningSettings
import splice.head.TestResponsesProvider
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.transport.TearAwareEvents
import splice.head.transport.WsRoundDrive
import splice.head.transport.WsRoundInputs
import splice.head.transport.WsRoundResult
import splice.head.transport.ZeroEventCapture
import splice.head.usage.OutputClamp
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.UsagePayloadBuilder
import splice.upstream.ClientFrameEmitted
import splice.upstream.ProviderTuning
import splice.upstream.RoundBody
import splice.upstream.WsRound
import splice.upstream.WsRoundRunner
import splice.upstream.retry.InflightGate
import splice.upstream.retry.TurnWatchdog
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTurnProviderActivityTest {
    private class Rig(tmp: Path) {
        var now = 1_000L
        val clock = ElapsedClock { now }
        val turns = LiveTurns(clock)
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "test",
                label = "test",
                catalog = ModelCatalog(
                    discoveryPrefix = "test--",
                    models = listOf(ModelEntry("model", "Model", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "model",
                auth = object : RefreshableAuthProvider {
                    override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic")
                    override suspend fun refresh(): Credentials? = null
                    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
                },
                baseUrl = "http://127.0.0.1:9",
                watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        )
        private val pipeline = TurnPipeline(
            CompactStats(tmp.resolve("compact.jsonl")),
            log = {},
            clampOutput = OutputClamp { it },
        )

        suspend fun drive(): TurnDrive {
            val slot = InflightGate({ 1 }).admittedSlot()
            val meta = TurnMeta(
                compact = false,
                showReasoning = ReasoningDisplay.TEXT,
                stream = true,
                originalModel = "model",
                upstreamModel = "model",
                clientMaxTokens = 100,
                effort = "high",
                summary = "detailed",
                budgetTokens = null,
            )
            turns.admitted(slot, meta, null)
            return TurnDrive(
                requestBody = buildJsonObject {},
                meta = meta,
                emitter = CollectingTerminal("model", UsagePayloadBuilder { buildJsonObject {} }),
                watchdog = TurnWatchdog(provider.watchdog),
                slot = slot,
                pipeline = pipeline,
                t0 = now,
                trace = null,
                perf = TurnPerf(clock = clock),
                turnHeaders = emptyMap(),
                signals = RunnerSignals(),
                channel = ClientChannel(
                    ImmediateSseWriter(writeRaw = {}, flushRaw = {}),
                    Mutex(),
                    AtomicBoolean(false),
                ),
                toolSearch = null,
            )
        }

        fun inputs(turn: TurnDrive, scope: CoroutineScope): WsRoundInputs = WsRoundInputs(
            turn,
            RoundBody.Text("{}"),
            turn.emitter,
            scope,
            Job(),
            ClientFrameEmitted { false },
            0L,
        )
    }

    private object Terminals : WsRoundRunner {
        override suspend fun attempt(
            bodyJson: String,
            meta: TurnMeta,
            turnHeaders: Map<String, String>,
            creds: Credentials,
        ): WsRound? = null

        override fun isFailureTerminal(event: JsonObject): Boolean =
            event["type"]?.jsonPrimitive?.content == "response.failed"

        override fun roundEnded(meta: TurnMeta, ok: Boolean) = Unit
        override fun roundBypassed(meta: TurnMeta) = Unit
    }

    @Test
    fun `raw SSE chunks reset idle even before a complete event or any client output`(@TempDir tmp: Path) = runTest {
        val rig = Rig(tmp)
        val drive = rig.drive()
        val body = ByteChannel(autoFlush = true)
        val received = launch {
            TearAwareEvents(rig.provider, {}).run(
                drive,
                body,
                ZeroEventCapture(),
                ClientFrameEmitted { false },
            ).toList()
        }
        try {
            rig.now += 500L
            body.writeStringUtf8(": provider heartbeat")
            runCurrent()
            assertTrue(drive.perfCounter(PerfKeys.SSE_BYTES_IN) > 0)
            assertEquals(0L, rig.turns.list().single().idleMs)
            rig.now += 100L
            body.writeStringUtf8("\n\n")
            runCurrent()
            assertEquals(0L, rig.turns.list().single().idleMs)
            rig.now += 300L
            runCurrent()
            assertEquals(300L, rig.turns.list().single().idleMs)
            assertEquals(900L, rig.turns.list().single().ageMs)
            assertEquals(0L, drive.perfCounter(PerfKeys.EVENTS_IN), "no parsed event exists to reset the reading")
        } finally {
            body.close()
            received.join()
            drive.slot.release()
        }
    }

    @Test
    fun `received WebSocket events update the same live turn even when the dialect emits nothing`(
        @TempDir tmp: Path,
    ) = runTest {
        val rig = Rig(tmp)
        val turn = rig.drive()
        val events = flow {
            repeat(2) {
                rig.now += 500L
                emit(Json.parseToJsonElement("""{"type":"provider.pulse"}""").jsonObject)
                assertEquals(0L, rig.turns.list().single().idleMs)
            }
        }
        try {
            WsRoundDrive(rig.provider, ZeroEventClassifier { _, outcome, _, _ -> outcome })
                .drive(rig.inputs(turn, this), Terminals, events)
            rig.now += 25L
            assertEquals(25L, rig.turns.list().single().idleMs)
            assertEquals(1_025L, rig.turns.list().single().ageMs)
        } finally {
            turn.slot.release()
        }
    }

    @Test
    fun `a WebSocket failure before output still counts as received provider activity`(@TempDir tmp: Path) = runTest {
        val rig = Rig(tmp)
        val turn = rig.drive()
        val events = flow {
            rig.now += 500L
            emit(Json.parseToJsonElement("""{"type":"response.failed"}""").jsonObject)
        }
        try {
            val outcome = WsRoundDrive(rig.provider, ZeroEventClassifier { _, result, _, _ -> result })
                .drive(rig.inputs(turn, this), Terminals, events)
            assertTrue(outcome is WsRoundResult.NeedsSse)
            rig.now += 25L
            assertEquals(25L, rig.turns.list().single().idleMs)
        } finally {
            turn.slot.release()
        }
    }
}
