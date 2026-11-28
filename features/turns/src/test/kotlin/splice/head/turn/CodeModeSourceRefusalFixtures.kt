package splice.head.turn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.failure.ServerFault
import splice.core.failure.Tear
import splice.core.turn.GatewayCustomCall
import splice.core.turn.ReasoningDisplay
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.Usage
import splice.head.round.RoundInterception
import splice.head.round.RoundRunners
import splice.head.round.RoundStrategy
import splice.head.round.RunnerSignals
import splice.head.round.testRoundSteps
import splice.head.testVoice
import splice.head.wire.SseEmitter
import splice.head.wire.SseEmitterFactory
import splice.head.wire.StreamWiring
import splice.head.wire.TurnTerminal
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import java.io.EOFException
import java.nio.file.Path

internal object CodeModeSourceRefusalFixtures {
    const val REFUSAL_WORDS = "Content blocked by provider policy."
    const val TEST_MODEL = "fixture-model"
    const val EXEC_TOOL = "exec"
    const val ROUND_INPUT_TOKENS = 120L
    const val ROUND_OUTPUT_TOKENS = 8L
    const val SECOND_INPUT_TOKENS = 200L
    const val SECOND_OUTPUT_TOKENS = 11L

    fun emitter(write: (String) -> Unit = {}, contentReached: Boolean = true): SseEmitter = SseEmitterFactory().create(
        write = write,
        model = TEST_MODEL,
        usagePayload = { buildJsonObject { } },
        streaming = StreamWiring(contentReached = { contentReached }),
        voice = testVoice,
    )

    fun bridge(
        tempDir: Path,
        completing: Boolean = false,
    ): CodexCodeModeBridge {
        val state = CodeModeStateLocation(tempDir.resolve("code-mode"), tempDir.resolve("state.json"))
        return CodexCodeModeBridge(CodeModeBridgeConfig({ nativeRuntime(completing) }, state))
    }

    fun strategy(
        bridge: CodexCodeModeBridge,
        terminal: TurnTerminal,
        tools: Set<String> = setOf("Read"),
        finish: suspend (TurnOutcome) -> Unit = {},
        post: suspend (WireSink) -> TurnOutcome,
    ): RoundStrategy {
        val turn = CodexCodeModeBridge.Turn("session", "conversation", TEST_MODEL, tools, emptyList())
        return RoundStrategy(
            emitter = terminal,
            runners = RoundRunners(
                steps = testRoundSteps(),
                key = "test",
                log = {},
                signals = RunnerSignals(watchdogFired = { false }, clientGone = { false }),
                finish = finish,
            ),
            postRoundToSink = { _, sink -> RoundResult.Outcome(post(sink)) },
            postRound = { error("redirected post required") },
            interception = RoundInterception(interceptor = bridge.interceptor(turn, disableParallel = false)),
        )
    }

    suspend fun completedExecRound(sink: WireSink): TurnOutcome.Success {
        val source = "return 'ok';"
        val raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"call-1","name":"$EXEC_TOOL","input":"$source"}""",
        ).jsonObject
        val started = GatewayCustomCall("call-1", EXEC_TOOL, "", raw)
        val completed = GatewayCustomCall("call-1", EXEC_TOOL, source, raw)
        val outcome = TurnOutcome.Success(
            hasToolUse = false,
            incomplete = false,
            usage = Usage(inputTokens = ROUND_INPUT_TOKENS, outputTokens = ROUND_OUTPUT_TOKENS),
            handoffs = RoundHandoffs(customCalls = listOf(completed)),
        )
        sink.customToolSource(CustomToolSource.Started(started))
        sink.customToolSource(CustomToolSource.Completed(completed, outcome))
        sink.customToolSource(CustomToolSource.Terminal)
        return outcome
    }

    suspend fun transientSource(sink: WireSink, kind: String): TurnOutcome.Failure {
        sink.addTextBlock("Starting work.")
        val raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"call-1","name":"$EXEC_TOOL","input":""}""",
        ).jsonObject
        val started = GatewayCustomCall("call-1", EXEC_TOOL, "", raw)
        sink.customToolSource(CustomToolSource.Started(started))
        sink.customToolSource(CustomToolSource.Delta(started.callId, "await tools.Read({});"))
        if (kind == "eof-io") throw EOFException("synthetic upstream EOF")
        sink.customToolSource(CustomToolSource.Terminal)
        val cause = if (kind == "eof-outcome") {
            FailureCause.Path.Torn(Tear.NO_TERMINAL, toolOpen = false)
        } else {
            FailureCause.Provider.ServerError(ServerFault.INTERNAL)
        }
        return TurnOutcome.Failure(
            cause = cause,
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence("upstream: $kind"),
            partial = TurnOutcome.PartialRound(
                text = RoundText(bodyText = "Starting work.", emittedText = true),
                usage = Usage(inputTokens = ROUND_INPUT_TOKENS, outputTokens = ROUND_OUTPUT_TOKENS),
            ),
        )
    }

    suspend fun refusedSource(
        sink: WireSink,
        partial: TurnOutcome.PartialRound? = null,
    ): TurnOutcome.Failure {
        if (partial == null) sink.addTextBlock("Starting work.")
        val raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"call-2","name":"$EXEC_TOOL","input":""}""",
        ).jsonObject
        val started = GatewayCustomCall("call-2", EXEC_TOOL, "", raw)
        sink.customToolSource(CustomToolSource.Started(started))
        sink.customToolSource(CustomToolSource.Delta(started.callId, "await tools.Read({});"))
        sink.customToolSource(CustomToolSource.Terminal)
        return TurnOutcome.Failure(
            cause = FailureCause.Provider.Refused(RefusedBy.POLICY, ProviderWordsFilter.of(REFUSAL_WORDS)),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence("upstream: content_filter $REFUSAL_WORDS"),
            partial = partial,
        )
    }

    suspend fun unfinishedExecEof(sink: WireSink): TurnOutcome {
        val raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"call-eof","name":"$EXEC_TOOL","input":""}""",
        ).jsonObject
        val started = GatewayCustomCall("call-eof", EXEC_TOOL, "", raw)
        sink.customToolSource(CustomToolSource.Started(started))
        sink.customToolSource(CustomToolSource.Delta(started.callId, "await tools.Read({});"))
        throw EOFException("synthetic upstream EOF")
    }

    suspend fun earlyWriteTear(sink: WireSink, kind: String): TurnOutcome {
        val raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"call-early","name":"$EXEC_TOOL","input":""}""",
        ).jsonObject
        val started = GatewayCustomCall("call-early", EXEC_TOOL, "", raw)
        sink.customToolSource(CustomToolSource.Started(started))
        sink.customToolSource(
            CustomToolSource.Delta(started.callId, """await tools.Write({file_path:"/w/a.txt",content:"partial"""),
        )
        if (kind.startsWith("refused-")) {
            val text = sink.openText()
            sink.textDelta(text, "Actually wait.")
            sink.closeBlock(text)
        }
        if (kind == "refused-eof-io") throw EOFException("synthetic upstream EOF")
        sink.customToolSource(CustomToolSource.Terminal)
        val prose = if (kind.startsWith("refused-")) "Actually wait." else ""
        return TurnOutcome.Failure(
            cause = FailureCause.Path.Torn(Tear.NO_TERMINAL, toolOpen = false),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence("upstream: $kind"),
            partial = TurnOutcome.PartialRound(
                text = RoundText(bodyText = prose, emittedText = prose.isNotEmpty()),
                usage = Usage(inputTokens = ROUND_INPUT_TOKENS, outputTokens = ROUND_OUTPUT_TOKENS),
            ),
        )
    }

    fun meta(): TurnMeta = TurnMeta(
        compact = false,
        reasoning = TurnReasoning(
            showReasoning = ReasoningDisplay.OFF,
            effort = "medium",
            summary = null,
            budgetTokens = null,
        ),
        route = TurnRoute(
            stream = true,
            originalModel = TEST_MODEL,
            upstreamModel = TEST_MODEL,
            clientMaxTokens = null,
        ),
    )

    fun requestBody(): JsonObject = Json.parseToJsonElement(
        """{"model":"$TEST_MODEL","store":false,"stream":true,"input":[{"role":"developer","content":"s"}]}""",
    ).jsonObject

    private fun nativeRuntime(completing: Boolean): NativeCodeModeLifecycle = object : NativeCodeModeLifecycle {
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = error("unused")

        override suspend fun startNativeSession(
            sessionKey: String,
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
            options: NativeCodeModeOptions,
        ): NativeCodeModeCell = if (!completing) {
            error("unfinished source must not start a native cell")
        } else {
            object : NativeCodeModeCell {
                override val cellId: String = "cell-1"
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = CodeModeStep.Completed("ok")
                override suspend fun submitResults(results: List<CodeModeResult>) = Unit
                override suspend fun observe(yieldTimeMs: Long): CodeModeStep = CodeModeStep.Completed("ok")
                override suspend fun terminate(): CodeModeStep = CodeModeStep.Completed("", terminated = true)
                override fun close() = Unit
            }
        }

        override fun close() = Unit
    }
}
