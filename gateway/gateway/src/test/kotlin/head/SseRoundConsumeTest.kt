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
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import kotlinx.coroutines.Dispatchers
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
import splice.spi.WatchdogFired
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

    private suspend fun drive(
        budget: WatchdogBudget = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
    ): TurnDrive = TurnDrive(
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
        watchdog = TurnWatchdog(budget),
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

    // DR-7, from codex-splice's review: the HeadServer acceptance arm could not pin this, because
    // by the time its round stalls it has already emitted thinking — so frameEmittedThisRound() is
    // true and the G5 branch is unreachable there either way. THIS is the state that separates a
    // reaped round from a transport tear: the watchdog fires before any client frame.
    //
    // Reaping now aborts the body channel, which surfaces as exactly the IOException a real tear
    // does. Without the watchdog test in TearAwareEvents.reissuable, this round would be rethrown
    // as StreamTornBeforeClient and silently re-POSTed by the reissue machinery — racing the
    // salvage-and-continue decision the terminal outcome is about to make, and spending an upstream
    // request on a backend that has just been observed to be stalled rather than broken.
    // Dispatchers.Default deliberately: the watchdog poller is a SIBLING coroutine that must sample
    // while the SSE read is parked, and the default single-threaded runBlocking event loop cannot
    // run both — the poller's own delay never resumes, so no budget can ever fire in that rig.
    @Test
    fun `a pre-content idle reap is an outcome, not a transport tear - DR-7`() = runBlocking(Dispatchers.Default) {
        val provider = provider()
        val consume = SseRoundConsume(
            provider,
            ZeroEventFailure(provider, log = {}),
            TurnTelemetry("codex", PerfStats(tmp.resolve("perf-dr7.jsonl")), log = {}, clock = ElapsedClock { 0L }),
            TearAwareEvents(provider, log = {}),
        )
        // STREAM-IDLE is the tier that reaps this, not firstByteTimeout — the opposite of what this
        // comment used to claim. The scenario's response.created is bytes on the wire, and
        // TearAwareEvents.onBytes marks them on the RAW read, so the watchdog has already flipped
        // tiers before the stall begins. "Pre-content" is about CLIENT FRAMES, not bytes.
        //
        // The budgets are now far apart so the arm can actually tell the tiers apart: with both at
        // 1s (as they were) either tier produced the same pass, which is how the false claim went
        // unnoticed. A generous 20s first-byte tier blows REAP_DEADLINE_MS if markByte never ran.
        val drive = drive(WatchdogBudget(20.seconds, 1.seconds, 30.seconds))
        val inputs = WsRoundInputs(
            drive = drive,
            bodyJson = "{}",
            sink = RecordingSink2(),
            scope = this,
            turnJob = Job(),
            // A STUB, and named as one: SseRoundDriver wires this to CONTENT_FRAMES_OUT. A constant
            // false happens to agree with the real probe here (response.created emits no client
            // frame), so this arm exercises the reissue gate's pre-content branch without observing
            // that the production wiring reaches the same answer. Pinning the real probe is a
            // separate arm on a separate seam, not something to smuggle in behind this one.
            frameEmittedThisRound = ClientFrameEmitted { false },
            eventsBase = 0,
        )
        try {
            // The assertion is that this RETURNS. A StreamTornBeforeClient thrown from here is the
            // regression, and it would fail this test by propagating rather than by an assertEquals.
            // preparePost/execute, NOT the buffered post the DR-90 arm uses: a buffered request
            // reads the WHOLE response before consume ever runs, so the round would be handed an
            // already-complete channel and the stall would be over before the watchdog existed.
            // (That is not a hypothetical — it is what this arm did until the body streamed.)
            client.preparePost("${mock.baseUrl}/v1/responses") {
                setBody("""{"instructions":"SCENARIO:idlepre"}""")
            }.execute { raw ->
                val t0 = System.currentTimeMillis()
                // The assertion is that this RETURNS. A StreamTornBeforeClient thrown from here is
                // the regression, and it fails this test by propagating rather than by an assert.
                val outcome = consume.consume(inputs, UpstreamResponse(raw))
                val tookMs = System.currentTimeMillis() - t0
                assertTrue(outcome is TurnOutcome.Failure, "a reaped round must report an outcome: $outcome")
                val fired = drive.watchdog.fired
                assertTrue(fired is WatchdogFired.Idle, "expected an Idle reap: $fired")
                // Names the TIER directly instead of inferring it from a pass. The ack is a byte,
                // so a correct watchdog reports sawFirstByte=true and judged this against
                // streamIdle; the arm previously asserted nothing about it and could not have
                // noticed if the first-byte tier had been the one that fired.
                assertTrue(
                    (fired as? WatchdogFired.Idle)?.sawFirstByte == true,
                    "the ack is a byte, so the stall is judged on streamIdle, not firstByteTimeout: $fired",
                )
                assertTrue(
                    tookMs < REAP_DEADLINE_MS,
                    "reaped by the 1s streamIdle cap, not by the 20s first-byte tier or the mock hanging up " +
                        "($tookMs ms)",
                )
            }
        } finally {
            inputs.turnJob.cancel()
            drive.slot.release()
        }
    }
}

// The mock stalls for 6s, and the arm's first-byte tier is 20s. A reap driven by the 1s STREAM-IDLE
// cap must land far inside both — which is what makes this deadline name the tier rather than just
// asserting that something eventually happened.
private const val REAP_DEADLINE_MS = 4_000L
