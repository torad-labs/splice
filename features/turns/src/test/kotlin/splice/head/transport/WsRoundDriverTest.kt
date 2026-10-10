// NEW (review of #72, WsRoundDriver.kt:85): the pre-content fallback decision is ONE boolean, and
// flipping it changes the user-visible failure mode — a WS round answered with a failure terminal
// would be served raw over the WebSocket, bypassing UpstreamClient's retry, its single-flight 401
// refresh and the shared 429 cooldown. Nothing tested it. These are HTTP-level, through a real
// HeadServer, because "the SSE path is reached" is only observable at the upstream.
//
// The two cases are opposites and both matter:
//   failure BEFORE any client frame -> abandon the WS round, SSE serves the turn (retry intact)
//   failure AFTER a frame           -> stay on the WS path; re-serving would duplicate output
package splice.head.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.WsAttemptTiming
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.RecordingSink2
import splice.head.TestResponsesProvider
import splice.head.admission.RequestMaterializationGate
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.headDeps
import splice.head.headStores
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.turn.TurnDrive
import splice.head.turn.TurnInputs
import splice.head.turn.ZeroEventClassifier
import splice.head.usage.OutputClamp
import splice.head.wire.BufferingWireSink
import splice.head.wire.ClientChannel
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.TurnTerminal
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.BuiltTurn
import splice.upstream.ClientFrameEmitted
import splice.upstream.NEVER_PINGED_MS
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.RoundBody
import splice.upstream.WsPathPulse
import splice.upstream.WsRound
import splice.upstream.WsRoundAbort
import splice.upstream.WsRoundRunner
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.TurnWatchdog
import splice.upstream.sse.WireSink
import splice.upstream.transport.UpstreamClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class WsFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-ws", "acct-ws")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

private fun selectedAnswers(inputs: WsRoundInputs, answers: MutableList<Pair<Boolean, Long>>): StreamAnswerObserver {
    val sender = WsFakeAuth()
    val account = PoolAccount(
        "synthetic",
        true,
        sender,
        AccountQuotaSource { null },
        RateLimitCooldown(ElapsedClock { 0L }),
    )
    inputs.drive.account = (AccountPool(listOf(account), WallClock { 999L }).select(null) as Selection.Chosen).account
    return StreamAnswerObserver { accepted, at, owner, key ->
        assertSame(sender, owner, "the selected pool sender, not the head login, owns this reply")
        assertEquals(CredentialKey.fromHeaders(inputs.drive.turnHeaders), key, "effective headers win")
        answers += accepted to at
    }
}

private fun ev(json: String): JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject

/** A synthetic ChatGPT policy refusal in the shape the backend ends a round with. */
private const val POLICY_REFUSAL =
    """{"type":"response.failed","response":{"id":"r1","status":"failed",""" +
        """"error":{"code":"cyber_policy","message":"This request was flagged. Try rephrasing."}}}"""

/** A round that opens one text part and streams one delta into it, so a client frame goes out. */
private val TEXT_ROUND = listOf(
    """{"type":"response.created","response":{"id":"r1"}}""",
    """{"type":"response.output_item.added","output_index":0,"item":{"type":"message","role":"assistant"}}""",
    """{"type":"response.content_part.added","output_index":0,"content_index":0,""" +
        """"part":{"type":"output_text","text":""}}""",
    """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"late text"}""",
)

private const val LATE_ERROR_SENTENCE = "Synthetic access check failed. Please try again."

/** A synthetic in-band error event of the shape the backend sends mid-round, with a request-class vendor code. */
private const val LATE_ERROR =
    """{"type":"error","error":{"type":"invalid_request_error","message":"$LATE_ERROR_SENTENCE"}}"""

/** A runner that replays a scripted round, so the driver's decision is the only variable.
 *  [throwAfter], when set, makes the round's flow throw once it has emitted that many events —
 *  standing in for an unexpected throw out of the translator/reducer on a real round. */
private class ScriptedRunner(
    private val events: List<String>,
    private val throwAfter: Int? = null,
    private val continuationEvents: List<String> = events,
    private val afterFirstSource: CompletableDeferred<Unit>? = null,
) : WsRoundRunner {
    var attempts = 0
    var bypassed = 0
    var endedOk = 0
    var endedNotOk = 0
    var flowCompletions = 0
    var aborts = 0
    var perf: TurnPerf? = null

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
        perf: TurnPerf?,
    ): WsRound {
        this.perf = perf
        perf?.beginUpstreamAttempt()
        return attempt(bodyJson, meta, turnHeaders, creds)
    }

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound {
        attempts += 1
        val selected = if (attempts == 1) events else continuationEvents
        val scripted = if (throwAfter == null) {
            flow {
                selected.forEachIndexed { index, event ->
                    emit(ev(event))
                    if (index == 2) afterFirstSource?.await()
                }
            }
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
private class StallingRunner(
    private val events: List<String>,
    /** The socket's last-ping age the round reports to the watchdog; never pinged by default. */
    private val pingAgoMs: Long = NEVER_PINGED_MS,
    private val abortFailure: RuntimeException? = null,
) : WsRoundRunner {
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
            abortFailure?.let { throw it }
        },
        pathPulse = WsPathPulse { pingAgoMs },
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
    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) = Unit
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
    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) = Unit
    override fun abandon() = Unit
}

/** A real responses provider with ONE member swapped. Interface delegation, not subclassing:
 *  TestResponsesProvider is final and wsRunner is a final override, and delegating keeps every
 *  other behaviour (buildTurn, the stream translator, the SSE path) genuinely real. */
private class ScriptedWsProvider(
    private val inner: TestResponsesProvider,
    private val runner: WsRoundRunner,
    private val bridge: CodexCodeModeBridge? = null,
) : Provider by inner {
    override val wsRunner: WsRoundRunner get() = runner

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val built = inner.buildTurn(body, compact, sessionId)
        val manager = bridge ?: return built
        val turn = CodexCodeModeBridge.Turn(
            "synthetic-ws-session",
            "synthetic-ws-conversation",
            built.meta.route.upstreamModel,
            setOf("SyntheticTool"),
        )
        return built.copy(roundInterceptor = manager.interceptor(turn, disableParallel = false))
    }

    override fun onHeadStop() {
        bridge?.onHeadStop()
    }
}

/** Provider and cold-flow construction shared by the driver's transport and timing controls. */
private class WsDriverFixture(private val tmp: Path, private val baseUrl: String) {
    fun provider(runner: WsRoundRunner, bridge: CodexCodeModeBridge? = null): Provider = ScriptedWsProvider(
        TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = WsFakeAuth(),
                locations = ProviderLocations(baseUrl = baseUrl),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        ),
        runner,
        bridge,
    )

    private val codexMeta = TurnMeta(
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

    suspend fun inputs(
        emitter: TurnTerminal,
        scope: CoroutineScope,
        budget: WatchdogBudget = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
        sink: WireSink = RecordingSink2(),
    ): WsRoundInputs {
        val slot = InflightGate(LiveLimit { 1 }).admittedSlot()
        val drive = TurnDrive(
            inputs = TurnInputs(
                built = BuiltTurn(
                    requestBody = buildJsonObject { },
                    meta = codexMeta,
                    extraHeaders = emptyMap(),
                    toolSearch = null,
                ),
                slot = slot,
                t0 = 0,
                perf = TurnPerf(),
                trace = null,
                markHandedOff = {},
            ),
            emitter = emitter,
            watchdog = TurnWatchdog(budget),
            pipeline = TurnPipeline(
                CompactStats(tmp.resolve("cold-flow-compact.jsonl")),
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
        return WsRoundInputs(
            drive = drive,
            body = RoundBody.Text("{}"),
            sink = sink,
            scope = scope,
            turnJob = Job(),
            frameEmittedThisRound = ClientFrameEmitted { false },
            eventsBase = 0,
        )
    }
}

/** Reasoning without cleartext is progress, but transport-only traffic cannot renew a turn. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchdogProgressRoundTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "ws-reasoning", "ws-text", "sse-reasoning", "sse-text", "ws-ping", "sse-ping",
            "ws-empty", "sse-empty", "ws-metadata", "sse-metadata", "ws-rate-limits", "sse-rate-limits",
        ],
    )
    fun `protocol progress survives the elapsed cap on both transports`(
        scenario: String,
        @TempDir tmp: Path,
    ) = runTest {
        val budget = WatchdogBudget(10.seconds, 10.seconds, 600.milliseconds)
        val dog = TurnWatchdog(budget, clock = ElapsedClock { testScheduler.currentTime })
        val fixture = WsDriverFixture(tmp, "http://127.0.0.1:9")
        val source = progressSource(scenario.substringAfter('-')) { delay(37) }
        val provider = fixture.provider(progressRunner(source))
        val original = fixture.inputs(RecordingTerminal(), this, budget)
        val inputs = original.copy(
            drive = original.drive.let {
                it.copy(
                    inputs = it.inputs.copy(perf = TurnPerf { testScheduler.currentTime }),
                    watchdog = dog,
                )
            },
        )
        val channel = ByteChannel(autoFlush = true)
        val feeder = if (scenario.startsWith("sse")) {
            launch {
                source.collect { channel.writeStringUtf8("data: " + it.toString() + "\n\n") }
                channel.close()
            }
        } else {
            null
        }
        val reading = async { consumeProgress(inputs, provider, channel.takeIf { feeder != null }) }
        val cap = dog.launchTotalCap(this, reading)
        var outcome: TurnOutcome? = null
        try {
            outcome = reading.await()
        } catch (_: CancellationException) {
            // A fired deadline cancels its owner; the assertions distinguish it from clean completion.
        } finally {
            cap.cancel()
            feeder?.cancel()
            channel.cancel(null)
            inputs.turnJob.cancel()
            inputs.drive.slot.release()
        }
        if (!scenario.endsWith("reasoning") && !scenario.endsWith("text")) {
            assertTrue(dog.fired is splice.upstream.retry.WatchdogFired.TotalCap)
            assertFalse(outcome is TurnOutcome.Success, "keepalives cannot buy another progress budget")
        } else {
            assertNull(dog.fired, "active $scenario was cut solely for elapsed wall time")
            assertTrue(outcome is TurnOutcome.Success, "the actual translator must consume completion: $outcome")
            assertTrue(testScheduler.currentTime > 600, "the stream really crossed its old cap")
        }
    }

    private fun progressSource(
        kind: String,
        awaitTick: suspend () -> Unit,
    ): kotlinx.coroutines.flow.Flow<JsonObject> = flow {
        repeat(30) { index ->
            awaitTick()
            emit(progressEvent(kind, index))
        }
        emit(ev("""{"type":"response.completed","response":{"status":"completed","output":[]}}"""))
    }

    private fun progressRunner(source: kotlinx.coroutines.flow.Flow<JsonObject>): WsRoundRunner =
        object : WsRoundRunner by ScriptedRunner(emptyList()) {
            override suspend fun attempt(
                bodyJson: String,
                meta: TurnMeta,
                turnHeaders: Map<String, String>,
                creds: Credentials,
            ): WsRound = WsRound(source, WsRoundAbort {}, WsPathPulse { 0 })

            override suspend fun attempt(
                bodyJson: String,
                meta: TurnMeta,
                turnHeaders: Map<String, String>,
                creds: Credentials,
                perf: TurnPerf?,
            ): WsRound = attempt(bodyJson, meta, turnHeaders, creds)
        }

    private suspend fun consumeProgress(
        inputs: WsRoundInputs,
        provider: Provider,
        channel: ByteChannel?,
    ): TurnOutcome? {
        if (channel == null) {
            return WsRoundDriver(provider, {}, ZeroEventClassifier { _, outcome, _, _ -> outcome }).run(inputs)
        }
        val events = TearAwareEvents(provider, {}).run(
            inputs.drive,
            channel,
            ZeroEventCapture(),
            inputs.frameEmittedThisRound,
        )
        val signals = splice.upstream.TurnSignals(
            watchdogFired = { inputs.drive.watchdog.fired },
            clientGone = { false },
        )
        return provider.streamTranslator(inputs.drive.meta, signals).driveTurn(events, inputs.sink)
    }

    private fun progressEvent(kind: String, index: Int): JsonObject = when (kind) {
        "text" -> ev("""{"type":"response.output_text.delta","delta":"synthetic"}""")
        "reasoning" -> {
            val stage = if (index % 2 == 0) "added" else "done"
            ev(
                """{"type":"response.output_item.$stage","output_index":${index / 2},"item":{""" +
                    """"type":"reasoning","id":"synthetic-${index / 2}","summary":[],""" +
                    """"encrypted_content":"synthetic-opaque"}}""",
            )
        }
        "empty" -> ev("""{"type":"response.output_text.delta","delta":""}""")
        "metadata" -> ev("""{"type":"codex.response.metadata"}""")
        "rate-limits" -> ev("""{"type":"codex.rate_limits"}""")
        else -> ev("""{"type":"ping"}""")
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsCompletionTest(@param:TempDir private val tmp: Path) {
    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private var built = 0

    @AfterAll
    fun close() {
        client.close()
        mock.stop()
    }

    private fun head(runner: ScriptedRunner, bridge: CodexCodeModeBridge? = null): HeadServer = HeadServer(
        provider = WsDriverFixture(tmp, mock.baseUrl).provider(runner, bridge),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
            log = {},
            seams = HeadDeps.HeadSeams(requestMaterializationGate = RequestMaterializationGate()),
        ).copy(stores = headStores(tmp, suffix = "-completion-${++built}")),
    )

    private fun responseBurst(kind: String): List<String> = buildList {
        add("""{"type":"response.created","response":{"id":"synthetic-burst"}}""")
        val type = if (kind == "text") "response.output_text.delta" else "response.reasoning_summary_text.done"
        val payload = if (kind == "text") "delta" else "text"
        val item = if (kind == "text") "message" else "reasoning"
        val part = if (kind == "text") "content_part" else "reasoning_summary_part"
        val partType = if (kind == "text") "output_text" else "summary_text"
        add(
            """{"type":"response.output_item.added","output_index":0,"item":{"id":"synthetic-item",""" +
                """"type":"$item","role":"assistant","content":[],"summary":[]}}""",
        )
        add(
            """{"type":"response.$part.added","item_id":"synthetic-item","output_index":0,""" +
                """"content_index":0,"summary_index":0,"part":{"type":"$partType","text":""}}""",
        )
        repeat(300) {
            add(
                """{"type":"$type","item_id":"synthetic-item","output_index":0,"content_index":0,""" +
                    """"summary_index":$it,"$payload":"synthetic part $it "}""",
            )
        }
    }

    private fun burstAggregate(kind: String, runner: ScriptedRunner) {
        val snapshot = runner.perf?.snapshot()
        println(
            "ws-burst kind=$kind attempts=${runner.attempts} ok=${runner.endedOk} failed=${runner.endedNotOk} " +
                "stream_end=${snapshot?.marks?.get(PerfKeys.STREAM_END)} " +
                "finish=${snapshot?.marks?.get(PerfKeys.FINISH)} " +
                "frames=${snapshot?.counters?.get(PerfKeys.FRAMES_OUT)} " +
                "start_rejected=${snapshot?.counters?.get(PerfKeys.CODE_MODE_START_REJECTED)}",
        )
    }

    private suspend fun boundedTurn(port: Int): String = withTimeout(5_000L) {
        client.post("http://127.0.0.1:$port/v1/messages") {
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":100,
                    "messages":[{"role":"user","content":"synthetic burst"}]}""",
            )
        }.bodyAsText()
    }

    @ParameterizedTest
    @ValueSource(strings = ["text", "thinking"])
    fun `a completed WS burst delivers its terminal without waiting for the watchdog`(kind: String) = runBlocking {
        val completed = """{"type":"response.completed","response":{"status":"completed",""" +
            """"usage":{"input_tokens":10,"output_tokens":300}}}"""
        val events = responseBurst(kind) + completed
        val runner = ScriptedRunner(events)
        val h = head(runner)
        h.start()
        try {
            val body = boundedTurn(h.port)
            assertTrue(body.contains("event: message_stop"), "the completed round must close the client message")
            assertFalse(body.contains("event: error"))
            assertEquals(1, runner.attempts)
            assertEquals(1, runner.endedOk)
        } finally {
            burstAggregate(kind, runner)
            h.stop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["text", "thinking"])
    fun `clean WS EOF after content continues into one completed round`(
        kind: String,
    ) = runBlocking {
        val runner = ScriptedRunner(
            responseBurst(kind),
            continuationEvents = listOf(
                """{"type":"response.output_text.delta","delta":"synthetic recovered answer"}""",
                """{"type":"response.completed","response":{"status":"completed",""" +
                    """"usage":{"input_tokens":10,"output_tokens":3}}}""",
            ),
        )
        val h = head(runner)
        h.start()
        try {
            val body = boundedTurn(h.port)
            assertTrue(body.contains("event: message_stop"), "the recovered answer must close the client message")
            assertFalse(body.contains("event: error"))
            assertTrue(body.contains("synthetic recovered answer"))
            assertEquals(2, runner.attempts)
            assertEquals(1, runner.endedOk)
            assertEquals(1, runner.endedNotOk)
        } finally {
            burstAggregate(kind, runner)
            h.stop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["completed", "eof", "worker-wait", "startup-wait"])
    fun `custom source ending releases the intercepted WS turn`(ending: String) = runBlocking {
        val runtime = SourceRuntime(ending)
        val state = CodeModeStateLocation(tmp.resolve("source-$ending"), tmp.resolve("legacy-$ending.json"))
        val lines = ConcurrentLinkedQueue<String>()
        val bridge = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, state, log = { lines += it }))
        val runner = ScriptedRunner(
            customSource(ending),
            afterFirstSource = when (ending) {
                "startup-wait" -> runtime.starting
                "eof", "worker-wait" -> runtime.advancing
                else -> null
            },
        )
        val h = head(runner, bridge)
        h.start()
        try {
            val body = boundedTurn(h.port)
            assertEquals(1, runtime.starts, "the request must traverse the real interceptor")
            assertEquals(1, runner.attempts)
            val rejected = runner.perf?.snapshot()?.counters?.get(PerfKeys.CODE_MODE_START_REJECTED)
            if (ending == "startup-wait") assertEquals(1L, rejected) else assertNull(rejected)
            assertEquals(
                if (ending == "startup-wait") 1 else 0,
                lines.count { it.startsWith("[code-mode] failed source released runtime startup") },
            )
            if (ending == "completed") {
                assertTrue(body.contains("event: message_stop"))
                assertTrue(body.contains("SyntheticTool"))
                assertFalse(body.contains("event: error"))
            } else {
                assertTrue(body.contains("event: error"), "torn custom source must fail without replay")
                assertEquals(1, runner.endedNotOk)
            }
        } finally {
            burstAggregate("custom-$ending", runner)
            h.stop()
        }
    }

    private fun customSource(ending: String): List<String> = buildList {
        val prefix =
            """{"id":"synthetic-exec","type":"custom_tool_call","call_id":"synthetic-call","name":"exec","input":"""
        val source = "synthetic source;".repeat(300)
        add("""{"type":"response.created","response":{"id":"synthetic-custom-response"}}""")
        add("""{"type":"response.output_item.added","output_index":0,"item":$prefix""}}""")
        repeat(300) {
            add(
                """{"type":"response.custom_tool_call_input.delta","item_id":"synthetic-exec",""" +
                    """"output_index":0,"delta":"synthetic source;"}""",
            )
        }
        if (ending != "worker-wait") {
            add("""{"type":"response.output_item.done","output_index":0,"item":$prefix"$source"}}""")
        }
        if (ending == "completed") {
            add(
                """{"type":"response.completed","response":{"status":"completed","output":[""" +
                    """$prefix"$source"}],"usage":{"input_tokens":10,"output_tokens":300}}}""",
            )
        }
    }

    private class SourceRuntime(private val mode: String) : CodeModeRuntime {
        var starts = 0
        val starting = CompletableDeferred<Unit>()
        val advancing = CompletableDeferred<Unit>()
        private val closed = CompletableDeferred<Unit>()

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = error("streaming source required")

        override suspend fun startStreaming(
            source: CodeModeSource,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            starts++
            if (mode == "startup-wait") {
                starting.complete(Unit)
                closed.await()
                throw IOException("synthetic startup closed")
            }
            return object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                    if (mode == "worker-wait") {
                        check(source.read() is CodeModeSourcePart.Delta)
                        advancing.complete(Unit)
                        closed.await()
                        throw IOException("synthetic worker closed")
                    }
                    advancing.complete(Unit)
                    while (true) {
                        when (val part = source.read()) {
                            is CodeModeSourcePart.Delta -> Unit
                            is CodeModeSourcePart.Failed -> throw IOException(part.error)
                            is CodeModeSourcePart.Complete -> return CodeModeStep.Calls(
                                listOf(CodeModeCall("synthetic-runtime-call", "SyntheticTool", buildJsonObject {})),
                            )
                        }
                    }
                }

                override fun close() {
                    closed.complete(Unit)
                }
            }
        }

        override fun close() {
            closed.complete(Unit)
        }
    }
}

/** The round that fails or tears before the client saw a frame, and is re-served over SSE instead: the value drive()
 *  answers, what the client sink is never given, and the line the fallback logs. Split from WsRoundDriverTest
 *  (detekt LargeClass, 2026-10-08). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsRoundReserveTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
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

    /** Heads built so far: each one's store files are keyed by it, since the port it binds (0, so
     *  the OS assigns one with no lease-then-bind window) is not known until it starts. */
    private var built = 0

    private fun provider(runner: WsRoundRunner): Provider = WsDriverFixture(tmp, mock.baseUrl).provider(runner)

    private fun head(
        runner: ScriptedRunner,
        bridge: CodexCodeModeBridge? = null,
        log: (String) -> Unit = {},
    ): HeadServer = HeadServer(
        provider = WsDriverFixture(tmp, mock.baseUrl).provider(runner, bridge),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
            log = log,
            seams = HeadDeps.HeadSeams(requestMaterializationGate = RequestMaterializationGate()),
        ).copy(stores = headStores(tmp, suffix = "-${++built}")),
    )

    private suspend fun coldFlowInputs(
        emitter: TurnTerminal,
        scope: CoroutineScope,
        budget: WatchdogBudget = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
        sink: WireSink = RecordingSink2(),
    ): WsRoundInputs = WsDriverFixture(tmp, mock.baseUrl).inputs(emitter, scope, budget, sink)

    private fun turn(port: Int): String = runBlocking {
        client.post("http://127.0.0.1:$port/v1/messages") {
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":100,
                    "messages":[{"role":"user","content":"hi"}]}""",
            )
        }.bodyAsText()
    }

    /** V4-114 PIN. The pre-content fallback is a VALUE on [WsRoundDrive.drive]'s return type now,
     *  not a thrown `splice.spi.WsRoundNeedsSse`: this test cannot compile against the old shape
     *  (drive returned TurnOutcome and threw). It pins the two facts the throw carried that the
     *  fallback depends on — the failure detail the log line is built from, and that the round is
     *  reported by NEITHER roundEnded arm and never marks STREAM_END, because a round about to be
     *  re-served over SSE must not commit its chaining state (WsRoundDriver owns the bypass). */
    @Test
    fun `a failure terminal before any client frame leaves drive as a NeedsSse value`() = runTest {
        val runner = ScriptedRunner(emptyList())
        val inputs = coldFlowInputs(RecordingTerminal(), this)
        val drive = WsRoundDrive(provider(runner), ZeroEventClassifier { _, outcome, _, _ -> outcome })
        val failed = ev(
            """{"type":"response.failed","response":{"id":"r1",""" +
                """"error":{"code":"server_error","message":"boom"}}}""",
        )

        val result = drive.drive(inputs, runner, flowOf(failed))

        assertEquals(WsRoundResult.NeedsSse("response.failed server_error boom"), result)
        assertEquals(0, runner.endedOk, "a round re-served over SSE is not a clean terminal")
        assertEquals(0, runner.endedNotOk, "and drive must not report it at all — the driver owns the bypass")
        assertNull(
            inputs.drive.perf.snapshot().marks[PerfKeys.STREAM_END],
            "STREAM_END belongs to a round that actually streamed",
        )
        inputs.drive.slot.release()
    }

    /** The re-served round writes NOTHING to the client: no terminal frame, no closing call. The flow ends
     *  without the deciding event instead of throwing, so the translator runs to its end, and what it would
     *  write there is the one thing the client must not see before the SSE round's own content. */
    @Test
    fun `a round re-served over SSE writes no terminal and no close to the client`() = runTest {
        val calls = mutableListOf<String>()
        val sink = object : WireSink by RecordingSink2() {
            override suspend fun closeAll() {
                calls += "closeAll"
            }
        }
        val runner = ScriptedRunner(emptyList())
        val inputs = coldFlowInputs(RecordingTerminal(), this, sink = sink)
        val drive = WsRoundDrive(provider(runner), ZeroEventClassifier { _, outcome, _, _ -> outcome })
        val failed = ev("""{"type":"response.failed","response":{"id":"r1","error":{"code":"server_error"}}}""")

        val result = drive.drive(inputs, runner, flowOf(failed))

        assertTrue(result is WsRoundResult.NeedsSse, "the round is re-served: $result")
        assertEquals(emptyList<String>(), calls, "the client sink saw no closing call: no terminal, no truncation")
        inputs.drive.slot.release()
    }

    /** FAILURE BEFORE ANY CLIENT FRAME -> the round is abandoned and SSE serves the turn, so the
     *  upstream POST happens and the client sees the normal answer. Without this the failure is
     *  delivered raw over the WebSocket, skipping retry / 401 refresh / 429 cooldown entirely. */
    @Test
    fun `a failure terminal before any client frame falls back to the SSE path`() {
        val runner = ScriptedRunner(listOf("""{"type":"response.failed","response":{"id":"r1"}}"""))
        val h = head(runner)
        runBlocking { h.start() }
        val port = h.port
        try {
            val before = mock.upstreamBodies.size
            val sse = turn(port)
            assertEquals(1, runner.attempts, "the overlay was tried")
            assertEquals(1, runner.bypassed, "and it reported the bypass so the chain is cleared")
            assertEquals(0, runner.endedOk, "a failure terminal is NOT a clean round")
            assertEquals(0, runner.endedNotOk, "the bypass is reported by roundBypassed, never roundEnded - V4-114")
            assertTrue(mock.upstreamBodies.size > before, "the SSE upstream must have served the turn")
            assertTrue(sse.contains("event: message_stop"), "the client sees a normal completed turn")
            assertTrue(AsyncFileIo.drain(), "the attempt counter reached its perf row")
            val row = ev(Files.readString(tmp.resolve("perf-$built.jsonl")).trim())
            assertEquals("2", row.getValue("attempts").toString(), "the websocket send and HTTP fallback both count")
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
        val h = head(runner)
        runBlocking { h.start() }
        val port = h.port
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

    @Test
    fun `the SSE fallback line carries a nested error object's message`() =
        assertFallbackLineCarries(
            """{"type":"response.failed","response":{"id":"r1","error":{"code":"server_error",""" +
                """"message":"No tool output found for call_1"}}}""",
            "server_error No tool output found for call_1",
        )

    @Test
    fun `the SSE fallback line carries a flat error event's message`() =
        assertFallbackLineCarries(
            """{"type":"error","code":null,"message":"No tool output found for call_1"}""",
            "error No tool output found for call_1",
        )

    @Test
    fun `the SSE fallback line carries a plain-string error, folded onto one line`() =
        assertFallbackLineCarries(
            """{"type":"error","error":"No tool output found\nfor call_1"}""",
            "error No tool output found for call_1",
        )

    private fun assertFallbackLineCarries(event: String, expected: String) {
        val runner = ScriptedRunner(listOf(event))
        val lines = mutableListOf<String>()
        val h = head(runner, log = { synchronized(lines) { lines += it } })
        runBlocking { h.start() }
        val port = h.port
        try {
            val sse = turn(port)
            assertTrue(sse.contains("event: message_stop"), "the SSE path served the turn")
            assertEquals(1, runner.bypassed, "the failure terminal before any frame is a bypass")
        } finally {
            runBlocking { h.stop() }
        }
        val line = synchronized(lines) { lines.single { "serving over SSE" in it } }
        assertTrue(expected in line, "the detail must survive the shape: $line")
        assertFalse('\n' in line.trimEnd('\n'), "one log line: $line")
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsRoundDriverTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
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

    /** Heads built so far: each one's store files are keyed by it, since the port it binds (0, so
     *  the OS assigns one with no lease-then-bind window) is not known until it starts. */
    private var built = 0

    private fun provider(runner: WsRoundRunner): Provider = WsDriverFixture(tmp, mock.baseUrl).provider(runner)

    private fun head(
        runner: ScriptedRunner,
        bridge: CodexCodeModeBridge? = null,
        log: (String) -> Unit = {},
    ): HeadServer = HeadServer(
        provider = WsDriverFixture(tmp, mock.baseUrl).provider(runner, bridge),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
            log = log,
            seams = HeadDeps.HeadSeams(requestMaterializationGate = RequestMaterializationGate()),
        ).copy(stores = headStores(tmp, suffix = "-${++built}")),
    )

    private suspend fun coldFlowInputs(
        emitter: TurnTerminal,
        scope: CoroutineScope,
        budget: WatchdogBudget = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
        sink: WireSink = RecordingSink2(),
    ): WsRoundInputs = WsDriverFixture(tmp, mock.baseUrl).inputs(emitter, scope, budget, sink)

    private fun turn(port: Int): String = runBlocking {
        client.post("http://127.0.0.1:$port/v1/messages") {
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":100,
                    "messages":[{"role":"user","content":"hi"}]}""",
            )
        }.bodyAsText()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(longs = [1_000, 4_000])
    fun `WebSocket gaps exclude client opening and measure blocked delivery`(attemptWaitMs: Long) = runTest {
        val perf = TurnPerf { testScheduler.currentTime }
        var opened = false
        val terminal = object : TurnTerminal by RecordingTerminal() {
            override suspend fun ensureStarted() {
                if (!opened) {
                    opened = true
                    delay(2_500)
                }
            }
        }
        val scripted = ScriptedRunner(emptyList())
        var observedPerf: TurnPerf? = null
        val answers = mutableListOf<Pair<Boolean, Long>>()
        val runner = object : WsRoundRunner by scripted {
            override suspend fun attempt(
                bodyJson: String,
                meta: TurnMeta,
                turnHeaders: Map<String, String>,
                creds: Credentials,
                perf: TurnPerf?,
            ): WsRound {
                observedPerf = perf
                delay(attemptWaitMs)
                return WsRound(
                    flow {
                        emit(ev("""{"type":"response.created","response":{"id":"synthetic"}}"""))
                        delay(3_000)
                        repeat(1000) { emit(ev("""{"type":"response.output_text.delta","delta":"synthetic"}""")) }
                        emit(ev("""{"type":"response.completed","response":{"status":"completed"}}"""))
                    },
                    WsRoundAbort {},
                )
            }
        }
        val cold = coldFlowInputs(terminal, this)
        val inputs = cold.copy(drive = cold.drive.copy(inputs = cold.drive.inputs.copy(perf = perf)))
        try {
            val result = WsRoundDriver(
                provider(runner),
                log = {},
                classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
                answerObserver = StreamAnswerObserver { accepted, at, _, _ -> answers += accepted to at },
                clock = { testScheduler.currentTime },
            ).run(inputs)
            assertTrue(result is TurnOutcome.Success)
            assertEquals(listOf(true to attemptWaitMs), answers, "one created frame, never one update per delta")
            assertSame(perf, observedPerf, "the head must forward this turn's recorder")
            assertEquals(maxOf(3_000L, attemptWaitMs), perf.snapshot().counters[PerfKeys.UP_GAP_MAX_MS])
            assertEquals(if (attemptWaitMs >= 2_000) 2L else 1L, perf.snapshot().counters[PerfKeys.UP_GAPS_2S])
            assertEquals(2_500L, perf.snapshot().counters[PerfKeys.UP_BLOCKED_MAX_MS])
            val expectedEnd = if (attemptWaitMs >= 3_000) "message_start" else "text_delta"
            assertEquals(expectedEnd, perf.snapshot().upstreamGapEnd?.wire)
        } finally {
            inputs.turnJob.cancel()
            inputs.drive.slot.release()
        }
    }

    @Test
    fun `a first WebSocket failure records one refusal without inventing HTTP status`() = runTest {
        val runner = ScriptedRunner(
            listOf("""{"type":"error","code":"permission_denied","message":"synthetic refusal"}"""),
        )
        val inputs = coldFlowInputs(RecordingTerminal(), this).let {
            val override = mapOf("Authorization" to "Bearer synthetic override")
            val inputs = it.drive.inputs
            it.copy(
                drive = it.drive.copy(inputs = inputs.copy(built = inputs.built.copy(extraHeaders = override))),
            )
        }
        val answers = mutableListOf<Pair<Boolean, Long>>()
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
            answerObserver = selectedAnswers(inputs, answers),
            clock = { 999L },
        )
        try {
            driver.run(inputs)
            assertEquals(listOf(false to 999L), answers)
        } finally {
            inputs.turnJob.cancel()
            inputs.drive.slot.release()
        }
    }

    /** A fold round buffers its text draft in the sink and counts no client frame for it. When a later
     *  failure terminal sends the round to SSE on that same sink, the draft must go with the failed round:
     *  SSE's answer then flushes alone, never beside the websocket round's obsolete text. */
    @Test
    fun `a websocket draft buffered before a re-serve over SSE is not flushed beside the SSE answer`() = runTest {
        val runner = ScriptedRunner(
            listOf(
                """{"type":"response.created","response":{"id":"r1"}}""",
                """{"type":"response.output_item.added","output_index":0,"item":{""" +
                    """"type":"message","role":"assistant"}}""",
                """{"type":"response.content_part.added","output_index":0,"content_index":0,""" +
                    """"part":{"type":"output_text","text":""}}""",
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"draft"}""",
                """{"type":"response.failed","response":{"id":"r1",""" +
                    """"error":{"code":"server_error","message":"boom"}}}""",
            ),
        )
        val client = RecordingSink2()
        val buffer = BufferingWireSink(client)
        val inputs = coldFlowInputs(RecordingTerminal(), this, sink = buffer)
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
        )
        try {
            assertNull(driver.run(inputs), "the round is re-served over SSE")
            buffer.flush() // what the SSE round's success does with the sink it inherited
            assertEquals(emptyList<String>(), client.opens, "the failed websocket round's draft reaches no client")
        } finally {
            inputs.turnJob.cancel()
            inputs.drive.slot.release()
        }
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
        val h = head(runner)
        runBlocking { h.start() }
        val port = h.port
        try {
            turn(port)
            assertEquals(1, runner.attempts, "the overlay served the round")
            assertEquals(0, runner.endedOk, "a round that threw is not a clean terminal")
            assertEquals(1, runner.endedNotOk, "and it MUST be reported not-ok so the chain is cleared")
        } finally {
            runBlocking { h.stop() }
        }
    }

    /** DR-91: the credential+attempt acquisition sat OUTSIDE the reporting try, so a cancellation
     *  landing while attempt() was in flight (post-send) unwound without roundEnded — the chain
     *  stayed anchored on a round that never finished and the NEXT turn chained onto it. Same
     *  CON-003 contract as the mid-round throw above, one suspension point earlier. */
    @Test
    fun `cancellation during the ws attempt still clears the chaining state`() = runTest {
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

    /** The fallback line names the failure in EVERY shape the dialect's reducer reads — an error
     *  object under `response`, the flat event, and a plain-string `error` (DR-109) — because the
     *  refusal it exists to attribute ("No tool output found for function call …") arrives in
     *  whichever the backend picks, and a multi-line message must stay one log line. */
    /** DR-7, THE WS HALF. The idle watchdog used to target the TURN job on this path, so a stalled
     *  WebSocket round killed the translator along with the round and the salvage died with it —
     *  the SSE path earned salvage-and-continue and this one was left behind. The watchdog now
     *  cancels a round-scoped job whose completion aborts the round's EVENT SOURCE, and the torn
     *  read folds into an honest terminal with the turn still alive underneath it.
     *
     *  Zero budgets so the first poll fires: the poller wakes only once the collector has parked at
     *  its stall, because runTest advances virtual time only when everything else is idle. */
    @Test
    fun `a stalled ws round is reaped and the turn survives it`() = runTest {
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

    /** 2026-09-05, THE OTHER STALL: a round past its tier on a socket the server is still pinging
     *  is a model reasoning in silence (gpt-6-astra: 300-750 s before its first delta), and the
     *  driver hands the round's own pulse to the poller so it is HELD, not reaped. Zero budgets as
     *  above, so the very first poll is past the tier; the hold is what keeps the abort count at
     *  zero. The stall never ends on its own, so the turn is cancelled to end the arm. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a silent ws round on a live path is held, not reaped`() = runTest {
        val runner = StallingRunner(
            listOf("""{"type":"response.created","response":{"id":"r1"}}"""),
            pingAgoMs = 8_000,
        )
        val inputs = coldFlowInputs(RecordingTerminal(), this, WatchdogBudget(0.seconds, 0.seconds, 30.seconds))
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
        )

        val round = launch { driver.run(inputs) }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, runner.aborts, "the poller must hold a silent round whose path is alive")
        val held = checkNotNull(inputs.drive.watchdog.held) { "the hold must be on the watchdog for the turn line" }
        assertEquals(8_000L, held.pingAgoMs)
        inputs.turnJob.cancel()
        round.join()
        inputs.drive.slot.release()

        assertEquals(1, runner.aborts, "cancelling the turn still aborts the held round beneath it")
        assertEquals(0, runner.endedOk)
    }

    /** THE REVERSE DIRECTION, and the reason the round job is PARENTED to the turn job rather than
     *  free-standing: a client hang-up or the whole-turn cap cancels the turn, and the round beneath
     *  it must still let go of its socket. A free-standing job would leave the round reading into a
     *  turn nobody is listening to. Long budgets here on purpose — the watchdog must not be what
     *  fires, or the arm would pass without proving the parent link. */
    // runCurrent, not advanceUntilIdle: the watchdog poller loops on delay forever, so advancing
    // virtual time to idle would never return. Opted in narrowly, on this arm alone.
    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(strings = ["clean", "throwing"])
    fun `cancelling the turn supervises the round abort`(variant: String) = runTest {
        val runner = StallingRunner(
            listOf("""{"type":"response.created","response":{"id":"r1"}}"""),
            abortFailure = if (variant == "throwing") {
                IllegalStateException("synthetic private transport bytes")
            } else {
                null
            },
        )
        val inputs = coldFlowInputs(RecordingTerminal(), this)
        val lines = mutableListOf<String>()
        val driver = WsRoundDriver(
            provider(runner),
            log = { lines += it },
            classifyZeroEvent = ZeroEventClassifier { _, outcome, _, _ -> outcome },
        )
        val round = launch { driver.run(inputs) }
        runCurrent()
        assertEquals(0, runner.aborts, "nothing has cancelled anything yet")
        assertDoesNotThrow { inputs.turnJob.cancel() }
        round.join()
        inputs.drive.slot.release()
        assertEquals(1, runner.aborts, "the cancelled turn must abort the round beneath it")
        assertEquals(variant == "throwing", lines.any { "websocket round abort failed" in it }, lines.toString())
        assertFalse(lines.any { "synthetic private transport bytes" in it })
    }

    /** THE BOUND on both of the above: an ordinary round must never abort itself. The round job is
     *  completed with NO cause on every clean exit, and a fix that cancelled it instead — or that
     *  aborted unconditionally in the finally — would pass the two arms above and tear down every
     *  healthy connection in the pool. */
    @Test
    fun `an ordinary ws round never aborts its own connection`() = runTest {
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
        assertEquals(1L, inputs.drive.perf.snapshot().counters[PerfKeys.ATTEMPTS], "accepted untraced WS send")
    }

    /** DR-7, THE SECOND DEFECT ON THIS PATH and one nothing pointed at: WsRoundDrive reported
     *  `roundEnded(ok = true)` for ANY return, a FAILURE outcome included. `ok` means "a clean,
     *  fully-consumed terminal" in the seam's own words, and anything else must CLEAR the chaining
     *  state — so a round that ended in a failure terminal, or that the zero-event classifier
     *  reclassified into one, still committed its chain and the next turn anchored onto a response
     *  the server never finished building. The one caller of that flag always said yes. */
    @Test
    fun `a ws round that ends in failure must not report a clean terminal`() = runTest {
        val runner = ScriptedRunner(listOf("""{"type":"response.created","response":{"id":"r1"}}"""))
        val inputs = coldFlowInputs(RecordingTerminal(), this)
        val driver = WsRoundDriver(
            provider(runner),
            log = {},
            classifyZeroEvent = ZeroEventClassifier { _, _, _, _ ->
                TurnOutcome.Failure(
                    "the classifier reclassified this round as failed",
                    cause = FailureCause.UPSTREAM_REPORTED,
                    phase = FailurePhase.MID_OUTPUT,
                )
            },
        )

        driver.run(inputs)
        inputs.drive.slot.release()

        assertEquals(0, runner.endedOk, "a failure outcome is not a clean terminal")
        assertEquals(1, runner.endedNotOk, "so the chain must be cleared, not committed")
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransportAttemptStartsTest(@param:TempDir private val tmp: Path) {
    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private var built = 0

    @AfterAll
    fun close() {
        client.close()
        mock.stop()
    }

    private fun head(runner: WsRoundRunner): HeadServer = HeadServer(
        provider = WsDriverFixture(tmp, mock.baseUrl).provider(runner),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
            seams = HeadDeps.HeadSeams(requestMaterializationGate = RequestMaterializationGate()),
        ).copy(stores = headStores(tmp, suffix = "-${++built}")),
    )

    private fun turn(port: Int): String = runBlocking {
        client.post("http://127.0.0.1:$port/v1/messages") {
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":100,
                    "messages":[{"role":"user","content":"hi"}]}""",
            )
        }.bodyAsText()
    }

    @Test
    fun `a websocket first-event timeout followed by SSE success retains two transport starts`() {
        val runner = object : WsRoundRunner by ScriptedRunner(emptyList()) {
            override suspend fun attempt(
                bodyJson: String,
                meta: TurnMeta,
                turnHeaders: Map<String, String>,
                creds: Credentials,
                perf: TurnPerf?,
            ): WsRound? {
                // ResponsesWsRunnerTest pins the real runner's matching first-event timeout.
                checkNotNull(perf).let(::WsAttemptTiming).sendAccepted()
                withTimeoutOrNull(1) { awaitCancellation() }
                return null
            }
        }
        val h = head(runner)
        runBlocking { h.start() }
        try {
            val before = mock.upstreamBodies.size
            assertTrue(turn(h.port).contains("event: message_stop"), "SSE completed the client turn")
            assertEquals(before + 1, mock.upstreamBodies.size, "one HTTP fallback was sent")
            assertTrue(AsyncFileIo.drain(), "the count reached its retained perf row")
            val row = ev(Files.readString(tmp.resolve("perf-$built.jsonl")).trim())
            assertEquals("2", row["transport_attempt_starts"]?.toString())
            assertEquals("1", row.getValue("attempts").toString(), "only SSE was accepted")
            assertFalse(PerfKeys.WS_REFUSED_TOO_LARGE in row, "this was not a 1009 refusal")
        } finally {
            runBlocking { h.stop() }
        }
    }

    @Test
    fun `a plain SSE success retains one transport start and one accepted attempt`() {
        val runner = object : WsRoundRunner by ScriptedRunner(emptyList()) {
            override suspend fun attempt(
                bodyJson: String,
                meta: TurnMeta,
                turnHeaders: Map<String, String>,
                creds: Credentials,
                perf: TurnPerf?,
            ): WsRound? = null // no wire attempt was started
        }
        val h = head(runner)
        runBlocking { h.start() }
        try {
            assertTrue(turn(h.port).contains("event: message_stop"))
            assertTrue(AsyncFileIo.drain())
            val row = ev(Files.readString(tmp.resolve("perf-$built.jsonl")).trim())
            assertEquals("1", row["transport_attempt_starts"]?.toString())
            assertEquals("1", row.getValue("attempts").toString())
        } finally {
            runBlocking { h.stop() }
        }
    }
}

/** How a WebSocket round's failure terminal reaches the client.
 *
 *  A POLICY REFUSAL before any client frame is the vendor's verdict on the REQUEST, so the round ends on it
 *  as its own failure: re-served over SSE, the identical context met the identical refusal (live
 *  2026-10-04, both re-sends that could be attributed: 2:18 to 2:21 and 6:05 PM CT).
 *
 *  ANY failure after a frame of the round stays on the websocket, because the client already holds that
 *  frame, and the turn ends on it as an error the client reads, never as a clean end over the partial. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsRoundFailureTest(@param:TempDir private val tmp: Path) {
    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }

    @AfterAll
    fun close() {
        client.close()
        mock.stop()
    }

    private fun fixture(): WsDriverFixture = WsDriverFixture(tmp, mock.baseUrl)

    private fun head(runner: ScriptedRunner): HeadServer = HeadServer(
        provider = fixture().provider(runner),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
            log = {},
            seams = HeadDeps.HeadSeams(requestMaterializationGate = RequestMaterializationGate()),
        ).copy(stores = headStores(tmp, suffix = "-refusal")),
    )

    private fun turn(port: Int): String = runBlocking {
        client.post("http://127.0.0.1:$port/v1/messages") {
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":100,
                    "messages":[{"role":"user","content":"hi"}]}""",
            )
        }.bodyAsText()
    }

    @Test
    fun `a policy refusal before any client frame ends the round as its own failure, not a NeedsSse`() = runTest {
        val runner = ScriptedRunner(emptyList())
        val inputs = fixture().inputs(RecordingTerminal(), this)
        val drive = WsRoundDrive(fixture().provider(runner), ZeroEventClassifier { _, outcome, _, _ -> outcome })

        val result = drive.drive(inputs, runner, flowOf(ev(POLICY_REFUSAL)))

        val outcome = (result as? WsRoundResult.Streamed)?.outcome as? TurnOutcome.Failure
        assertEquals(FailureCause.CONTENT_FILTERED, outcome?.cause, "the refusal was not the round's ending: $result")
        assertEquals(true, outcome?.traits?.permanent, "a refusal re-sent is the identical refusal")
        assertEquals(1, runner.endedNotOk, "the refused round clears its chain")
        inputs.drive.slot.release()
    }

    /** The same refusal end to end: one WebSocket attempt, no SSE request, and the client reads the
     *  bad-request class with the vendor's own sentence, which it does not re-send. */
    @Test
    fun `a policy refusal before any client frame reaches the client as invalid_request_error, never re-sent`() {
        val runner = ScriptedRunner(listOf(POLICY_REFUSAL))
        val h = head(runner)
        runBlocking { h.start() }
        try {
            val before = mock.upstreamBodies.size
            val sse = turn(h.port)
            assertEquals(0, runner.bypassed, "the refusal was re-served over SSE")
            assertEquals(before, mock.upstreamBodies.size, "an SSE request re-sent the refused context")
            assertEquals(1, runner.attempts, "the refused context was re-sent over the websocket")
            assertTrue(sse.contains("\"type\":\"invalid_request_error\""), "not the bad-request class: $sse")
            assertTrue(sse.contains("This request was flagged. Try rephrasing."), "the vendor's sentence: $sse")
        } finally {
            runBlocking { h.stop() }
        }
    }

    /** The upstream's in-band error event after this round's text reached the client. Its code names the request
     *  class but no refusal, so the refusal arm does not end it; the turn's ordinary failure ending does. */
    @Test
    fun `an upstream error after this round's content ends the turn as an error the client reads`() {
        val runner = ScriptedRunner(TEXT_ROUND + LATE_ERROR)
        val h = head(runner)
        runBlocking { h.start() }
        try {
            val before = mock.upstreamBodies.size
            val sse = turn(h.port)
            assertEquals(0, runner.bypassed, "the client held this round's text, and the round was re-served")
            assertEquals(before, mock.upstreamBodies.size, "an SSE request re-sent the context")
            assertTrue(sse.contains("late text"), "the text the client already read: $sse")
            assertTrue(sse.contains("event: error"), "the failed turn ended without an error frame: $sse")
            assertTrue(sse.contains(LATE_ERROR_SENTENCE), "the vendor's sentence: $sse")
            assertFalse(sse.contains("\"stop_reason\":\"end_turn\""), "the failed turn ended as a clean end: $sse")
        } finally {
            runBlocking { h.stop() }
        }
    }
}
