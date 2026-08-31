// NEW (DR-90): the per-ATTEMPT zero-event baseline. UpstreamClient invokes the consume lambda once
// per attempt and REUSES the round's WsRoundInputs across stream reissues (G5) — so a round-scoped
// events baseline counts attempt 1's parsed frames against a reissued attempt, and the G2
// zero-event reclassify is skipped exactly when it matters: the reissue came back as a dead-head
// body (HTML login 200, zero SSE frames). Driven at the SseRoundConsume seam because the reissue
// itself needs a mid-stream connection RST the in-process mock cannot force (HeadServerReviewTest's
// torn-stream note); calling consume twice with the same inputs IS the UpstreamClient contract.
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import mock.MockChatGptUpstream
import mock.RecordingSink2
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.gateway.compact.CompactStats
import splice.gateway.head.SseRoundConsume
import splice.gateway.head.TearAwareEvents
import splice.gateway.head.TurnDrive
import splice.gateway.head.TurnTelemetry
import splice.gateway.head.WsRoundInputs
import splice.gateway.head.ZeroEventFailure
import splice.gateway.perf.PerfStats
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.round.RunnerSignals
import splice.gateway.usage.OutputClamp
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.TurnTerminal
import splice.provider.codex.CodexProvider
import splice.spi.ClientFrameEmitted
import splice.spi.InflightGate
import splice.spi.LiveLimit
import splice.spi.Provider
import splice.spi.ProviderTuning
import splice.spi.TurnWatchdog
import splice.spi.UpstreamResponse
import splice.spi.WireSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
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
    override suspend fun emitError(type: ErrorType, message: String) = Unit
    override fun abandon() = Unit
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseRoundConsumeTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private lateinit var tmp: Path

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("sse-consume")
    }

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun provider(): Provider = CodexProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = ConsumeFakeAuth(),
            baseUrl = mock.baseUrl,
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )

    private suspend fun drive(): TurnDrive = TurnDrive(
        bodyJson = "{}",
        requestBody = buildJsonObject { },
        meta = TurnMeta(
            compact = false,
            showReasoning = ReasoningDisplay.TEXT,
            stream = true,
            originalModel = "claude-codex--gpt-5.6-sol",
            upstreamModel = "gpt-5.6-sol",
            clientMaxTokens = 100,
            effort = "high",
            summary = "detailed",
            budgetTokens = null,
        ),
        emitter = NoopTerminal(),
        watchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
        slot = InflightGate(LiveLimit { 1 }).acquire(),
        pipeline = TurnPipeline(
            CompactStats(tmp.resolve("compact-dr90.jsonl")),
            log = {},
            clampOutput = OutputClamp { it },
        ),
        t0 = 0,
        upstreamModel = "gpt-5.6-sol",
        perf = TurnPerf(),
        turnHeaders = emptyMap(),
        signals = RunnerSignals(),
        channel = ClientChannel(
            ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = {}),
            Mutex(),
            AtomicBoolean(false),
        ),
        toolSearch = null,
    )

    private suspend fun fetch(scenario: String): UpstreamResponse =
        UpstreamResponse(
            client.post("${mock.baseUrl}/v1/responses") {
                setBody("""{"instructions":"SCENARIO:$scenario"}""")
            },
        )

    @Test
    fun `a reissued attempt re-baselines the zero-event count - DR-90`() = runBlocking {
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
            bodyJson = "{}",
            sink = RecordingSink2(),
            scope = this,
            turnJob = Job(),
            frameEmittedThisRound = ClientFrameEmitted { false },
            eventsBase = 0,
        )
        try {
            // Attempt 1: events flow, then the stream ends without a terminal — the shape that
            // precedes a G5 reissue. Its outcome is not under test; its EVENTS_IN pollution is.
            val first = consume.consume(inputs, fetch("truncated"))
            assertTrue(drive.perfCounter(PerfKeys.EVENTS_IN) > 0, "attempt 1 must have parsed events")
            // Attempt 2 (the reissue, same inputs per the UpstreamClient contract): an HTML login
            // page with ZERO events. Pre-fix the round-scoped baseline counted attempt 1's events
            // and the G2 auth diagnosis was skipped — an undiagnosable generic error instead.
            val second = consume.consume(inputs, fetch("zero_event_auth"))
            val failure = second as TurnOutcome.Failure
            assertEquals(ErrorType.AUTHENTICATION, failure.type, "zero-event auth body must classify: $failure")
            assertTrue(failure.message.contains("claudex login"), "login hint expected: ${failure.message}")
            // Control: attempt 1's verdict must stay untouched — its round had real events.
            assertFalse(first is TurnOutcome.Failure && first.type == ErrorType.AUTHENTICATION)
        } finally {
            inputs.turnJob.cancel()
            drive.slot.release()
        }
    }
}
