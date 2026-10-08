// NEW: V4-10 pre-request turn expiry must use the existing total-cap translator terminal.
package splice.head.turn

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.dialect.responses.ReasoningSettings
import splice.head.MockChatGptUpstream
import splice.head.TestResponsesProvider
import splice.head.admission.TurnQuota
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.transport.SseRoundConsume
import splice.head.transport.SseRoundPost
import splice.head.transport.TearAwareEvents
import splice.head.transport.WsRoundInputs
import splice.head.transport.ZeroEventFailure
import splice.head.usage.OutputClamp
import splice.head.usage.UsageStore
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.UsagePayloadBuilder
import splice.upstream.ClientFrameEmitted
import splice.upstream.ProviderTuning
import splice.upstream.RetryNotice
import splice.upstream.RoundBody
import splice.upstream.TurnSignals
import splice.upstream.WsRound
import splice.upstream.WsRoundRunner
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.retry.WatchdogFired
import splice.upstream.transport.RemainingTurnWait
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class AccountTurnTimeoutTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `expired continuation uses the canonical watchdog terminal without an upstream call`() = runBlocking {
        val rig = Rig(tmp)
        val drive = rig.drive()
        val turnJob = Job()
        try {
            val inputs = WsRoundInputs(
                drive,
                RoundBody.Text("{}"),
                rig.terminal,
                this,
                turnJob,
                ClientFrameEmitted { false },
                0L,
            )
            assertTrue(rig.post.post(inputs) is TurnOutcome.Success)
            assertEquals(0L, drive.perf.snapshot().marks[PerfKeys.STREAM_END])
            // The prior POST completed; this next POST has attempt zero but no whole-turn budget left.
            rig.expire()
            val callsBeforeContinuation = rig.calls
            val attemptsBeforeContinuation = drive.perf.snapshot().counters[PerfKeys.ATTEMPTS]
            val actual = rig.post.post(inputs) as TurnOutcome.Failure
            val expectedTerminal = rig.newTerminal()
            val expected = rig.provider.streamTranslator(
                drive.meta,
                TurnSignals(
                    watchdogFired = { WatchdogFired.TotalCap(30_000L) },
                    clientGone = { false },
                ),
            ).driveTurn(emptyFlow(), expectedTerminal) as TurnOutcome.Failure

            assertEquals(callsBeforeContinuation, rig.calls, "the expired continuation must make zero upstream calls")
            assertEquals(1, rig.calls, "only the completed prior POST reached upstream")
            assertEquals(expected, actual, "use the translator's total-cap explanation and salvage policy")
            assertFalse(actual.providerReported)
            assertNull(actual.partial, "whole-turn expiry must not offer another continuation")
            assertEquals(attemptsBeforeContinuation, drive.perf.snapshot().counters[PerfKeys.ATTEMPTS])
            assertEquals(30_000L, drive.perf.snapshot().marks[PerfKeys.STREAM_END])
            val actualTag = drive.pipeline.finishStream(rig.terminal, actual, drive.meta, 30_000L)
            val expectedTag = drive.pipeline.finishStream(expectedTerminal, expected, drive.meta, 30_000L)
            assertEquals("failure:overloaded_error", actualTag)
            assertEquals(expectedTag, actualTag)
            assertEquals(expectedTerminal.httpStatus(), rig.terminal.httpStatus())
            assertEquals(expectedTerminal.responseBody(), rig.terminal.responseBody())
        } finally {
            turnJob.cancel()
            drive.slot.release()
            rig.client.close()
            rig.mock.stop()
        }
    }

    @Test
    fun `an expired unsent continuation preserves the prior observed raw bill`() = runBlocking {
        val rig = Rig(tmp)
        val drive = rig.drive()
        val turnJob = Job()
        try {
            val first = rig.inputs(drive, this, turnJob)
            val known = rig.post.post(first) as TurnOutcome.Success
            drive.recordRawRound(known)
            val expected = TurnBill.counters(known.usage)
            rig.expire()
            val continuation = rig.inputs(drive, this, turnJob)
            val before = drive.perf.snapshot().counters[PerfKeys.TRANSPORT_ATTEMPT_STARTS]
            val ended = rig.post.post(continuation)
            drive.recordRawRound(ended)
            assertEquals(1, rig.calls, "the expired continuation never reached HTTP")
            assertEquals(before, drive.perf.snapshot().counters[PerfKeys.TRANSPORT_ATTEMPT_STARTS])
            assertEquals(expected, TurnBill.counters(checkNotNull(drive.rawRoundUsage())))
            assertEquals(0L, drive.rawRoundUsage()!!.cutRounds)
            assertEquals(0L, drive.rawRoundUsage()!!.absorbed.rounds)
        } finally {
            turnJob.cancel()
            drive.slot.release()
            rig.client.close()
            rig.mock.stop()
        }
    }

    @Test
    fun `a started websocket before the HTTP skip remains a request with missing usage`() = runBlocking {
        val rig = Rig(tmp)
        val drive = rig.drive()
        val turnJob = Job()
        try {
            val first = rig.inputs(drive, this, turnJob)
            val known = rig.post.post(first) as TurnOutcome.Success
            drive.recordRawRound(known)
            rig.expire()
            val continuation = rig.inputs(drive, this, turnJob)
            var websocketCalls = 0
            val runner = object : WsRoundRunner {
                override suspend fun attempt(
                    bodyJson: String,
                    meta: TurnMeta,
                    turnHeaders: Map<String, String>,
                    creds: Credentials,
                ): WsRound? {
                    websocketCalls++
                    return null
                }

                override fun isFailureTerminal(event: JsonObject): Boolean = false
                override fun roundEnded(meta: TurnMeta, ok: Boolean) = Unit
                override fun roundBypassed(meta: TurnMeta) = Unit
            }
            runner.attempt("{}", drive.meta, emptyMap(), Credentials.Bearer("synthetic"), drive.perf)
            val ended = rig.post.post(continuation)
            assertEquals(1, websocketCalls, "the WebSocket attempt began before its HTTP fallback was skipped")
            drive.recordRawRound(ended)
            val total = checkNotNull(drive.rawRoundUsage())
            assertEquals(1, rig.calls, "the expired HTTP fallback still makes no send")
            assertNull(TurnBill.counters(total)[PerfKeys.IN_TOKENS], "the started WebSocket's input is unknown")
            assertEquals(known.usage.inputTokens, total.absorbed.inputTokens)
            assertEquals(1L, total.absorbed.rounds, "only the earlier known request is absorbed")
            assertEquals(0L, total.cutRounds, "absence remains visible in the final request, not a fabricated zero")
        } finally {
            turnJob.cancel()
            drive.slot.release()
            rig.client.close()
            rig.mock.stop()
        }
    }

    private class Rig(tmp: Path) {
        val mock = MockChatGptUpstream()
        val calls: Int get() = mock.upstreamBodies.size
        private var remainingMs = 30_000L
        private var elapsedMs = 0L
        private val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("test")
            override suspend fun refresh(): Credentials? = null
            override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
        }
        val client = HttpClient(CIO)
        private val upstream = UpstreamClient(
            totalTimeoutMs = 30_000L,
            maxRetries = 3,
            client = client,
            clock = ElapsedClock { 0L },
        )
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = auth,
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        )
        private val pipeline = TurnPipeline(
            CompactStats(tmp.resolve("compact.jsonl")),
            log = {},
            clampOutput = OutputClamp { it },
        )
        val terminal = newTerminal()
        val post = SseRoundPost(
            provider,
            upstream,
            UsageStore(tmp.resolve("usage.json"), tmp.resolve("rate-limit.json")),
            // V4-99 item 1: SseRoundPost's quota seam is now a required TurnQuota (non-null) rather
            // than a nullable tracker, so the test supplies the empty one instead of null.
            TurnQuota(null, emptyMap(), null),
            SseRoundConsume(
                provider,
                ZeroEventFailure(provider, log = {}),
                TurnTelemetry("codex", PerfStats(tmp.resolve("perf.jsonl")), log = {}, clock = ElapsedClock { 0L }),
                TearAwareEvents(provider, log = {}),
            ),
            RetryNotice {},
        )

        fun inputs(drive: TurnDrive, scope: CoroutineScope, turnJob: Job): WsRoundInputs = WsRoundInputs(
            drive,
            RoundBody.Text("{}"),
            terminal,
            scope,
            turnJob,
            ClientFrameEmitted { false },
            0L,
        )

        fun newTerminal(): CollectingTerminal =
            CollectingTerminal("gpt-5.6-sol", UsagePayloadBuilder { buildJsonObject {} })

        fun expire() {
            remainingMs = 0L
            elapsedMs = 30_000L
        }

        suspend fun drive(): TurnDrive = TurnDrive(
            requestBody = buildJsonObject {},
            meta = TurnMeta(
                compact = false,
                showReasoning = ReasoningDisplay.TEXT,
                stream = false,
                originalModel = "claude-codex--gpt-5.6-sol",
                upstreamModel = "gpt-5.6-sol",
                clientMaxTokens = 100,
                effort = "high",
                summary = "detailed",
                budgetTokens = null,
            ),
            emitter = terminal,
            watchdog = TurnWatchdog(provider.watchdog, ElapsedClock { 0L }),
            slot = InflightGate(LiveLimit { 1 }).admittedSlot(),
            pipeline = pipeline,
            t0 = 0L,
            trace = null,
            perf = TurnPerf { elapsedMs },
            turnHeaders = emptyMap(),
            signals = RunnerSignals(),
            channel = ClientChannel(
                ImmediateSseWriter(writeRaw = {}, flushRaw = {}),
                Mutex(),
                AtomicBoolean(false),
            ),
            toolSearch = null,
            remainingTurnWait = RemainingTurnWait { remainingMs },
        )
    }
}
