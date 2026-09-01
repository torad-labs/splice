// NEW (review of #72, WsRoundDriver.kt:85): the pre-content fallback decision is ONE boolean, and
// flipping it changes the user-visible failure mode — a WS round answered with a failure terminal
// would be served raw over the WebSocket, bypassing UpstreamClient's retry, its single-flight 401
// refresh and the shared 429 cooldown. Nothing tested it. These are HTTP-level, through a real
// HeadServer, because "the SSE path is reached" is only observable at the upstream.
//
// The two cases are opposites and both matter:
//   failure BEFORE any client frame -> abandon the WS round, SSE serves the turn (retry intact)
//   failure AFTER a frame           -> stay on the WS path; re-serving would duplicate output
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import mock.MockChatGptUpstream
import mock.RecordingSink2
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.head.RequestMaterializationGate
import splice.gateway.head.TurnDrive
import splice.gateway.head.WsRoundDriver
import splice.gateway.head.WsRoundInputs
import splice.gateway.head.ZeroEventClassifier
import splice.gateway.perf.PerfStats
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.round.RunnerSignals
import splice.gateway.usage.OutputClamp
import splice.gateway.usage.UsageStore
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
import splice.spi.UpstreamClient
import splice.spi.WireSink
import splice.spi.WsRound
import splice.spi.WsRoundAbort
import splice.spi.WsRoundRunner
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private class WsFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-ws", "acct-ws")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

private fun ev(json: String): JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject

/** A runner that replays a scripted round, so the driver's decision is the only variable.
 *  [throwAfter], when set, makes the round's flow throw once it has emitted that many events —
 *  standing in for an unexpected throw out of the translator/reducer on a real round. */
private class ScriptedRunner(private val events: List<String>, private val throwAfter: Int? = null) : WsRoundRunner {
    var attempts = 0
    var bypassed = 0
    var endedOk = 0
    var endedNotOk = 0
    var flowCompletions = 0
    var aborts = 0

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound {
        attempts += 1
        val scripted = if (throwAfter == null) {
            flowOf(*events.map(::ev).toTypedArray())
        } else {
            flow {
                events.take(throwAfter).forEach { emit(ev(it)) }
                error("scripted translator blow-up")
            }
        }
        return WsRound(scripted.onCompletion { flowCompletions += 1 }, WsRoundAbort { aborts += 1 })
    }

    override fun isFailureTerminal(event: JsonObject): Boolean =
        event["type"].toString().trim('"') in setOf("response.failed", "response.error", "error")

    override fun roundEnded(meta: TurnMeta, ok: Boolean) {
        if (ok) endedOk += 1 else endedNotOk += 1
    }

    override fun roundBypassed(meta: TurnMeta) {
        bypassed += 1
    }
}

/**
 * A round that STALLS after its scripted events and ends only when the head aborts it — the fake of
 * a WebSocket whose server went quiet mid-round.
 *
 * Its [WsRound.abort] releases the gate with an IOException, which is what the real transport does:
 * killing the connection closes its inbox and WsRoundStream turns a closed inbox into
 * `IOException("websocket stream ended mid-round")`. The fake reproduces the SHAPE the head depends
 * on — a torn read, not a cancelled collector — because that is the whole difference between
 * reaping a round and killing the turn with it.
 */
private class StallingRunner(private val events: List<String>) : WsRoundRunner {
    var aborts = 0
    var endedOk = 0
    var endedNotOk = 0
    private val torn = CompletableDeferred<Unit>()

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound = WsRound(
        events = flow {
            events.forEach { emit(ev(it)) }
            torn.await()
            throw IOException("websocket stream ended mid-round")
        },
        abort = WsRoundAbort {
            aborts += 1
            torn.complete(Unit)
        },
    )

    override fun isFailureTerminal(event: JsonObject): Boolean = false

    override fun roundEnded(meta: TurnMeta, ok: Boolean) {
        if (ok) endedOk += 1 else endedNotOk += 1
    }

    override fun roundBypassed(meta: TurnMeta) = Unit
}

/** A terminal that records instead of throwing — the cold-flow arms that are NOT about a failing
 *  start need the round to actually run. */
private class RecordingTerminal : TurnTerminal, WireSink by RecordingSink2() {
    override val hasEnded: Boolean = false

    override suspend fun ensureStarted() = Unit
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override suspend fun emitError(type: ErrorType, message: String) = Unit
    override fun abandon() = Unit
}

/** DR-91: stands in for a turn cancelled while attempt() is in flight — the WS send may already
 *  have advanced the runner's chaining state when the cancellation unwinds. */
private class CancellingAttemptRunner(private val cancel: CancellationException) : WsRoundRunner {
    var endedNotOk = 0
    var bypassed = 0

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound? = throw cancel

    override fun isFailureTerminal(event: JsonObject): Boolean = false

    override fun roundEnded(meta: TurnMeta, ok: Boolean) {
        if (!ok) endedNotOk += 1
    }

    override fun roundBypassed(meta: TurnMeta) {
        bypassed += 1
    }
}

private class ThrowingStartTerminal(
    private val failure: CancellationException,
) : TurnTerminal, WireSink by RecordingSink2() {
    override val hasEnded: Boolean = false

    override suspend fun ensureStarted(): Unit = throw failure
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override suspend fun emitError(type: ErrorType, message: String) = Unit
    override fun abandon() = Unit
}

/** A real codex provider with ONE member swapped. Interface delegation, not subclassing:
 *  CodexProvider is final and wsRunner is a final override, and delegating keeps every other
 *  behaviour (buildTurn, the stream translator, the SSE path) genuinely real. */
private class ScriptedWsProvider(
    private val inner: CodexProvider,
    private val runner: WsRoundRunner,
) : Provider by inner {
    override val wsRunner: WsRoundRunner get() = runner
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsRoundDriverTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private lateinit var tmp: Path

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("ws-driver")
    }

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun freshPort(): Int = ServerSocket(0).use { it.localPort }

    private fun provider(runner: WsRoundRunner): Provider = ScriptedWsProvider(
        CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = WsFakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        ),
        runner,
    )

    private fun head(port: Int, runner: ScriptedRunner): HeadServer = HeadServer(
        provider = provider(runner),
        listenPort = port,
        deps = HeadDeps(
            upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 2),
            inferenceToken = "test-inference-token",
            gate = InflightGate({ 0 }),
            shadow = ShadowClassifier(log = {}),
            compactStats = CompactStats(tmp.resolve("compact-$port.jsonl")),
            usageStore = UsageStore(tmp.resolve("usage-$port.json"), tmp.resolve("rl-$port.json")),
            perfStats = PerfStats(tmp.resolve("perf-$port.jsonl")),
            log = {},
            requestMaterializationGate = RequestMaterializationGate(2),
        ),
    )

    private suspend fun coldFlowInputs(
        emitter: TurnTerminal,
        scope: CoroutineScope,
        budget: WatchdogBudget = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
    ): WsRoundInputs {
        val slot = InflightGate(LiveLimit { 1 }).acquire()
        val drive = TurnDrive(
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
            emitter = emitter,
            watchdog = TurnWatchdog(budget),
            slot = slot,
            pipeline = TurnPipeline(
                CompactStats(tmp.resolve("cold-flow-compact.jsonl")),
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
        return WsRoundInputs(
            drive = drive,
            bodyJson = "{}",
            sink = RecordingSink2(),
            scope = scope,
            turnJob = Job(),
            frameEmittedThisRound = ClientFrameEmitted { false },
            eventsBase = 0,
        )
    }

    private fun turn(port: Int): String = runBlocking {
        client.post("http://127.0.0.1:$port/v1/messages") {
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":100,
                    "messages":[{"role":"user","content":"hi"}]}""",
            )
        }.bodyAsText()
    }

    @Test
    fun `cancellation while opening the client stream cleans the acquired cold flow`() = runTest {
        val runner = ScriptedRunner(listOf("""{"type":"response.created","response":{"id":"r1"}}"""))
        val cancellation = CancellationException("cancel while starting the client stream")
        val inputs = coldFlowInputs(ThrowingStartTerminal(cancellation), this)
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
        )
        var thrown: CancellationException? = null

        try {
            driver.run(inputs)
        } catch (failure: CancellationException) {
            thrown = failure
        } finally {
            inputs.turnJob.cancel()
            inputs.drive.slot.release()
        }

        assertSame(cancellation, thrown, "the genuine cancellation must propagate unchanged")
        assertEquals(1, runner.flowCompletions, "the acquired flow must unwind through its cleanup")
        assertEquals(1, runner.endedNotOk, "the abandoned round must clear its chaining state")
        assertEquals(0, runner.endedOk)
        assertEquals(0, runner.bypassed)
    }

    /** AN UNEXPECTED THROW still reports the round. [WsRoundRunner.roundEnded]'s contract is that
     *  anything but a clean terminal must CLEAR the chaining state; before CON-003 the driver caught
     *  only WsRoundNeedsSse, so any other exception left the round reported by neither roundEnded
     *  nor roundBypassed and the chain stayed anchored on a response the server never finished —
     *  the next turn would then anchor onto context that does not exist. */
    @Test
    fun `an unexpected throw mid-round clears the chain instead of leaving it anchored`() {
        val runner = ScriptedRunner(
            listOf("""{"type":"response.created","response":{"id":"r1"}}"""),
            throwAfter = 1,
        )
        val port = freshPort()
        val h = head(port, runner)
        runBlocking { h.start() }
        try {
            turn(port)
            assertEquals(1, runner.attempts, "the overlay served the round")
            assertEquals(0, runner.endedOk, "a round that threw is not a clean terminal")
            assertEquals(1, runner.endedNotOk, "and it MUST be reported not-ok so the chain is cleared")
        } finally {
            runBlocking { h.stop() }
        }
    }

    /** FAILURE BEFORE ANY CLIENT FRAME -> the round is abandoned and SSE serves the turn, so the
     *  upstream POST happens and the client sees the normal answer. Without this the failure is
     *  delivered raw over the WebSocket, skipping retry / 401 refresh / 429 cooldown entirely. */
    @Test
    fun `a failure terminal before any client frame falls back to the SSE path`() {
        val runner = ScriptedRunner(listOf("""{"type":"response.failed","response":{"id":"r1"}}"""))
        val port = freshPort()
        val h = head(port, runner)
        runBlocking { h.start() }
        try {
            val before = mock.upstreamBodies.size
            val sse = turn(port)
            assertEquals(1, runner.attempts, "the overlay was tried")
            assertEquals(1, runner.bypassed, "and it reported the bypass so the chain is cleared")
            assertEquals(0, runner.endedOk, "a failure terminal is NOT a clean round")
            assertTrue(mock.upstreamBodies.size > before, "the SSE upstream must have served the turn")
            assertTrue(sse.contains("event: message_stop"), "the client sees a normal completed turn")
        } finally {
            runBlocking { h.stop() }
        }
    }

    /** FAILURE AFTER A FRAME -> the client has already seen output, so re-serving over SSE would
     *  duplicate it. The round stays on the WS path and no upstream POST is made. */
    @Test
    fun `a failure terminal AFTER a client frame stays on the websocket path`() {
        val runner = ScriptedRunner(
            listOf(
                """{"type":"response.created","response":{"id":"r1"}}""",
                """{"type":"response.output_item.added","output_index":0,""" +
                    """"item":{"type":"message","role":"assistant"}}""",
                """{"type":"response.content_part.added","output_index":0,"content_index":0,""" +
                    """"part":{"type":"output_text","text":""}}""",
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"hello"}""",
                """{"type":"response.failed","response":{"id":"r1"}}""",
            ),
        )
        val port = freshPort()
        val h = head(port, runner)
        runBlocking { h.start() }
        try {
            val before = mock.upstreamBodies.size
            val sse = turn(port)
            // Rounds > 1 are the head's own re-anchor retries, which a post-content failure gets on
            // EITHER transport — pre-existing behaviour and not what this test is about.
            assertTrue(runner.attempts >= 1, "the overlay served the round")
            assertEquals(
                0,
                runner.bypassed,
                "content was already emitted, so the pre-content fallback must NOT fire — re-serving " +
                    "over SSE would duplicate output the client already has",
            )
            assertEquals(before, mock.upstreamBodies.size, "no SSE upstream request may be made")
            assertTrue(sse.contains("hello"), "the content the client already saw is preserved")
            assertFalse(sse.isEmpty())
            // DR-7, the same defect the unit arm names, seen end to end: this round really did end
            // in a failure terminal, so it must NOT have committed its chain.
            assertEquals(0, runner.endedOk, "a failed round is not a clean terminal at any level")
            assertTrue(runner.endedNotOk >= 1, "and every one of its attempts must clear the chain")
        } finally {
            runBlocking { h.stop() }
        }
    }

    /** DR-91: the credential+attempt acquisition sat OUTSIDE the reporting try, so a cancellation
     *  landing while attempt() was in flight (post-send) unwound without roundEnded — the chain
     *  stayed anchored on a round that never finished and the NEXT turn chained onto it. Same
     *  CON-003 contract as the mid-round throw above, one suspension point earlier. */
    @Test
    fun `cancellation during the ws attempt still clears the chaining state - DR-91`() = runTest {
        val cancel = CancellationException("cancelled mid-send")
        val runner = CancellingAttemptRunner(cancel)
        val inputs = coldFlowInputs(ThrowingStartTerminal(CancellationException("unused")), this)
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
        )
        var thrown: CancellationException? = null

        try {
            driver.run(inputs)
        } catch (failure: CancellationException) {
            thrown = failure
        } finally {
            inputs.turnJob.cancel()
            inputs.drive.slot.release()
        }

        assertSame(cancel, thrown, "the genuine cancellation must propagate unchanged")
        assertEquals(1, runner.endedNotOk, "the aborted acquisition must clear the chaining state")
        assertEquals(0, runner.bypassed, "an aborted acquisition is not a bypass")
    }

    /** DR-7, THE WS HALF. The idle watchdog used to target the TURN job on this path, so a stalled
     *  WebSocket round killed the translator along with the round and the salvage died with it —
     *  the SSE path earned salvage-and-continue and this one was left behind. The watchdog now
     *  cancels a round-scoped job whose completion aborts the round's EVENT SOURCE, and the torn
     *  read folds into an honest terminal with the turn still alive underneath it.
     *
     *  Zero budgets so the first poll fires: the poller wakes only once the collector has parked at
     *  its stall, because runTest advances virtual time only when everything else is idle. */
    @Test
    fun `a stalled ws round is reaped and the turn survives it - DR-7`() = runTest {
        val runner = StallingRunner(listOf("""{"type":"response.created","response":{"id":"r1"}}"""))
        val inputs = coldFlowInputs(RecordingTerminal(), this, WatchdogBudget(0.seconds, 0.seconds, 30.seconds))
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
        )

        val outcome = try {
            driver.run(inputs)
        } finally {
            inputs.drive.slot.release()
        }

        assertEquals(1, runner.aborts, "the idle watchdog must abort THIS ROUND's event source")
        assertTrue(
            inputs.turnJob.isActive,
            "and must not cancel the turn — the fold loop still owns it, which is the whole repair",
        )
        assertTrue(outcome is TurnOutcome.Failure, "the torn read folds into an honest terminal, not a dead turn")
        assertEquals(0, runner.endedOk, "a reaped round is not a clean terminal")
        assertEquals(1, runner.endedNotOk, "so its chaining state must be cleared")
    }

    /** THE REVERSE DIRECTION, and the reason the round job is PARENTED to the turn job rather than
     *  free-standing: a client hang-up or the whole-turn cap cancels the turn, and the round beneath
     *  it must still let go of its socket. A free-standing job would leave the round reading into a
     *  turn nobody is listening to. Long budgets here on purpose — the watchdog must not be what
     *  fires, or the arm would pass without proving the parent link. */
    // runCurrent, not advanceUntilIdle: the watchdog poller loops on delay forever, so advancing
    // virtual time to idle would never return. Opted in narrowly, on this arm alone.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling the turn still aborts the round beneath it - DR-7`() = runTest {
        val runner = StallingRunner(listOf("""{"type":"response.created","response":{"id":"r1"}}"""))
        val inputs = coldFlowInputs(RecordingTerminal(), this)
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
        )

        val round = launch { driver.run(inputs) }
        runCurrent()
        assertEquals(0, runner.aborts, "nothing has cancelled anything yet")
        inputs.turnJob.cancel()
        round.join()
        inputs.drive.slot.release()

        assertEquals(1, runner.aborts, "the cancelled turn must abort the round beneath it")
    }

    /** THE BOUND on both of the above: an ordinary round must never abort itself. The round job is
     *  completed with NO cause on every clean exit, and a fix that cancelled it instead — or that
     *  aborted unconditionally in the finally — would pass the two arms above and tear down every
     *  healthy connection in the pool. */
    @Test
    fun `an ordinary ws round never aborts its own connection - DR-7`() = runTest {
        val runner = ScriptedRunner(listOf("""{"type":"response.created","response":{"id":"r1"}}"""))
        val inputs = coldFlowInputs(RecordingTerminal(), this)
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
        )

        driver.run(inputs)
        inputs.drive.slot.release()

        assertEquals(0, runner.aborts, "a round that ended on its own must not have its source torn")
    }

    /** DR-7, THE SECOND DEFECT ON THIS PATH and one nothing pointed at: WsRoundDrive reported
     *  `roundEnded(ok = true)` for ANY return, a FAILURE outcome included. `ok` means "a clean,
     *  fully-consumed terminal" in the seam's own words, and anything else must CLEAR the chaining
     *  state — so a round that ended in a failure terminal, or that the zero-event classifier
     *  reclassified into one, still committed its chain and the next turn anchored onto a response
     *  the server never finished building. The one caller of that flag always said yes. */
    @Test
    fun `a ws round that ends in failure must not report a clean terminal - DR-7`() = runTest {
        val runner = ScriptedRunner(listOf("""{"type":"response.created","response":{"id":"r1"}}"""))
        val inputs = coldFlowInputs(RecordingTerminal(), this)
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, _, _, _ ->
                TurnOutcome.Failure(ErrorType.API_ERROR, "the classifier reclassified this round as failed")
            },
        )

        driver.run(inputs)
        inputs.drive.slot.release()

        assertEquals(0, runner.endedOk, "a failure outcome is not a clean terminal")
        assertEquals(1, runner.endedNotOk, "so the chain must be cleared, not committed")
    }
}
