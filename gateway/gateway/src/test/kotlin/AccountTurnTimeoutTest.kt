// NEW: V4-10 pre-request turn expiry must use the existing total-cap translator terminal.
import head.admittedSlot
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import mock.MockChatGptUpstream
import mock.TestResponsesProvider
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
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.gateway.compact.CompactStats
import splice.gateway.head.SseRoundConsume
import splice.gateway.head.SseRoundPost
import splice.gateway.head.TearAwareEvents
import splice.gateway.head.TurnDrive
import splice.gateway.head.TurnQuota
import splice.gateway.head.TurnTelemetry
import splice.gateway.head.WsRoundInputs
import splice.gateway.head.ZeroEventFailure
import splice.gateway.perf.PerfStats
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.round.RunnerSignals
import splice.gateway.usage.OutputClamp
import splice.gateway.usage.UsageStore
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.CollectingTerminal
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.UsagePayloadBuilder
import splice.spi.ClientFrameEmitted
import splice.spi.InflightGate
import splice.spi.LiveLimit
import splice.spi.ProviderTuning
import splice.spi.RemainingTurnWait
import splice.spi.RetryNotice
import splice.spi.TurnSignals
import splice.spi.TurnWatchdog
import splice.spi.UpstreamClient
import splice.spi.WatchdogFired
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
                "{}",
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
            firstByteTimeoutMs = 10_000L,
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
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
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
            upstreamModel = "gpt-5.6-sol",
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
