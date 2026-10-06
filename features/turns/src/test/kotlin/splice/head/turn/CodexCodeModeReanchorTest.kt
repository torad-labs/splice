package splice.head.turn

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.GatewayCustomCall
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.dialect.responses.StreamTurnContext
import splice.dialect.responses.reasoning.EmitEncryptedReasoning
import splice.dialect.responses.reasoning.ResponsesReanchorController
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.stream.ResponsesStreamTranslator
import splice.head.round.RoundInterception
import splice.head.round.RoundStrategy
import splice.head.round.RunnerSignals
import splice.head.wire.SseEmitterFactory
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.ReanchorRound
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.WireSink
import java.io.IOException
import java.nio.file.Path

class CodexCodeModeReanchorTest {
    @TempDir
    lateinit var tempDir: Path

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `local runtime failure after visible output cannot reanchor or regenerate source`(prose: Boolean) = runTest {
        val runtime = Runtime(fail = true)
        var posts = 0
        var finished: TurnOutcome? = null
        val strategy = strategy(runtime, { finished = it }) {
            posts++
            outer("call-$posts").copy(
                bodyText = if (prose) "visible prose" else "",
                emittedText = prose,
                thinkingText = "visible reasoning",
                emittedThinking = true,
            )
        }
        strategy.run(body, null, ResponsesReanchorController({ null }, maxContinuations = 1))
        val failure = finished as TurnOutcome.Failure
        assertEquals(1, posts, "local failure must not produce an upstream marker retry")
        assertEquals(1, runtime.starts)
        assertNull(failure.partial)
        assertEquals(Usage(19, 7, 5, 3), failure.salvagedUsage)
    }

    @Test
    fun `genuine upstream interruption after completed script retains ordinary recovery`() = runTest {
        val runtime = Runtime(fail = false)
        var posts = 0
        var finished: TurnOutcome? = null
        val strategy = strategy(runtime, { finished = it }) {
            when (posts++) {
                0 -> outer("first")
                1 -> TurnOutcome.Failure(
                    "upstream interrupted",
                    cause = FailureCause.UPSTREAM_REPORTED,
                    phase = FailurePhase.MID_OUTPUT,
                    providerReported = true,
                    partial = TurnOutcome.PartialRound(bodyText = "visible", emittedText = true),
                )
                else -> TurnOutcome.Success(false, false, Usage(23, 2), messageClosed = true)
            }
        }
        strategy.run(body, null, ResponsesReanchorController({ null }, maxContinuations = 1))
        assertTrue(finished is TurnOutcome.Success, finished.toString())
        assertEquals(3, posts)
        assertEquals(1, runtime.starts)
        assertEquals(9, (finished as TurnOutcome.Success).usage.outputTokens)
    }

    @ParameterizedTest
    @ValueSource(strings = ["prose", "empty", "first-byte"])
    fun `a torn code-mode round with no exec retains ordinary healing`(mode: String) = runTest {
        val runtime = Runtime(fail = false)
        var posts = 0
        var finished: TurnOutcome? = null
        val strategy = strategy(runtime, { finished = it }) {
            if (posts++ == 0) {
                TurnOutcome.Failure(
                    "synthetic prose transport tear",
                    cause = if (mode == "first-byte") FailureCause.UPSTREAM_CONN_RESET else FailureCause.UPSTREAM_TRUNCATED,
                    phase = if (mode == "first-byte") FailurePhase.FIRST_BYTE else FailurePhase.MID_OUTPUT,
                    partial = TurnOutcome.PartialRound(
                        bodyText = if (mode == "prose") "visible synthetic prose" else "",
                        emittedText = mode == "prose",
                    ),
                )
            } else {
                TurnOutcome.Success(false, false, Usage(23, 2), messageClosed = true)
            }
        }
        strategy.run(body, null, ResponsesReanchorController({ null }, maxContinuations = 1))
        assertTrue(finished is TurnOutcome.Success, finished.toString())
        assertEquals(2, posts, "prose recovery must not be mistaken for replay of a script")
        assertEquals(0, runtime.starts)
    }

    @ParameterizedTest
    @CsvSource(
        "1,1,false,false,false",
        "2,1,true,false,false",
        "1,2,true,false,false",
        "2,1,true,true,false",
        "1,1,false,false,true",
        "2,1,true,true,true",
        "0,1,true,false,false",
        "0,1,true,true,false",
    )
    fun `reanchored source resumes from the history the client actually received`(
        eventsBeforeCut: Int,
        cuts: Int,
        streamed: Boolean,
        reload: Boolean,
        changedBaseline: Boolean,
    ) = runTest {
        val lines = mutableListOf<String>()
        val runtime = PendingRuntime()
        val state = CodeModeStateLocation(tempDir.resolve("code-mode"), tempDir.resolve("state.json"))
        val config = CodeModeBridgeConfig({ runtime }, state, log = LogSink(lines::add))
        var bridge = CodexCodeModeBridge(config)
        val frames = mutableListOf<String>()
        val sink = SseEmitterFactory().create(frames::add, "model", { buildJsonObject { } })
        val turn = CodexCodeModeBridge.Turn("session", "conversation", "gpt-6-astra", setOf("Read"))
        val recovery = RecoveryCase(eventsBeforeCut, cuts, streamed)
        var finished: TurnOutcome? = null
        val strategy = recoveryStrategy(bridge, turn, sink, recovery) { finished = it }
        try {
            strategy.run(recovery.original, null, recovery.controller)
            assertTrue((finished as TurnOutcome.Success).hasToolUse)
            assertEquals(cuts + 1, recovery.posted.size)
            val received = clientHistory(frames)
            val callback = JsonScalars.str(received.last(), "call_id")!!
            if (reload) {
                bridge.onHeadStop()
                bridge = CodexCodeModeBridge(config)
            }
            var nextPosted: JsonObject? = null
            val next = recovery.nextRequest(received, changedBaseline)
            val answering = turn.copy(toolResults = listOf(CodeModeResult(callback, "read")))
            bridge.interceptor(answering, disableParallel = false)
                .intercept(next.toString(), sink) {
                    nextPosted = event(it)
                    TurnOutcome.Success(false, false, Usage(), messageClosed = true)
                }
            val abandons = lines.filter { "logical history does not match its persisted baseline" in it }
            if (changedBaseline) {
                assertEquals(1, abandons.size, "recovery must not weaken stable-baseline integrity")
            } else {
                assertEquals(emptyList<String>(), abandons, "client-visible history must place the reanchored record")
            }
            val privateMarker = recovery.privateMarker()
            assertTrue(
                privateMarker !in checkNotNull(nextPosted).getValue("input").jsonArray,
                "private recovery item $privateMarker must not survive in ${checkNotNull(nextPosted)}",
            )
            assertEquals(if (reload || changedBaseline) 1 else 2, runtime.advances, "source is never regenerated")
            assertEquals(1, runtime.starts, "source is never regenerated")
        } finally {
            bridge.onHeadStop()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a completed reanchored script retains its exact upstream recovery input`(streamed: Boolean) = runTest {
        val runtime = PendingRuntime(completeFirst = true)
        val state = CodeModeStateLocation(tempDir.resolve("code-mode"), tempDir.resolve("state.json"))
        val bridge = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, state))
        val sink = SseEmitterFactory().create({}, "model", { buildJsonObject { } })
        val turn = CodexCodeModeBridge.Turn("session", "conversation", "gpt-6-astra", setOf("Read"))
        val recovery = RecoveryCase(2, 1, streamed)
        var finished: TurnOutcome? = null
        try {
            recoveryStrategy(bridge, turn, sink, recovery) { finished = it }
                .run(recovery.original, null, recovery.controller)
            assertTrue(finished is TurnOutcome.Success, finished.toString())
            assertEquals(3, recovery.posted.size, "a completed script posts its original upstream view")
            assertEquals(1, runtime.starts)
            assertEquals(1, runtime.advances)
        } finally {
            bridge.onHeadStop()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a second script after recovered completion owns the whole client echo without replaying its prefix`(
        streamed: Boolean,
    ) = runTest {
        val runtime = PendingRuntime(completeFirst = true, parkSecond = true)
        val state = CodeModeStateLocation(tempDir.resolve("code-mode"), tempDir.resolve("state.json"))
        val lines = mutableListOf<String>()
        val bridge = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, state, log = LogSink(lines::add)))
        val frames = mutableListOf<String>()
        val sink = SseEmitterFactory().create(frames::add, "model", { buildJsonObject { } })
        val turn = CodexCodeModeBridge.Turn("session", "conversation", "gpt-6-astra", setOf("Read"))
        val recovery = RecoveryCase(2, 1, streamed, secondScript = true)
        try {
            recoveryStrategy(bridge, turn, sink, recovery) {}
                .run(recovery.original, null, recovery.controller)
            val received = clientHistory(frames)
            val callback = JsonScalars.str(received.last(), "call_id")!!
            val answering = turn.copy(toolResults = listOf(CodeModeResult(callback, "read")))
            var posted: JsonObject? = null
            bridge.interceptor(answering, disableParallel = false)
                .intercept(recovery.nextRequest(received, false).toString(), sink) {
                    posted = event(it)
                    TurnOutcome.Success(false, false, Usage(), messageClosed = true)
                }
            assertTrue(lines.none { "abandoned record" in it || "interrupted extra=STEERING" in it }, lines.toString())
            assertEquals(3, runtime.advances)
            val items = checkNotNull(posted).getValue("input").jsonArray
            val texts = items.mapNotNull { item -> JsonScalars.str(item as? JsonObject, "content") }
            assertEquals(1, texts.count { it == "prefix prefix suffix " }, "the first source prefix is replayed once")
            assertEquals(1, texts.count { it == "second suffix " }, "the second source adds only its own prose")
        } finally {
            bridge.onHeadStop()
        }
    }

    private fun recoveryStrategy(
        bridge: CodexCodeModeBridge,
        turn: CodexCodeModeBridge.Turn,
        sink: WireSink,
        recovery: RecoveryCase,
        finish: suspend (TurnOutcome) -> Unit,
    ) = RoundStrategy(
        key = "test",
        log = {},
        emitter = sink,
        signals = RunnerSignals(watchdogFired = { false }, clientGone = { false }),
        postRoundToSink = { request, target -> recovery.post(request, target) },
        postRound = { error("redirectable post expected") },
        finish = { finish(it) },
        interception = RoundInterception(interceptor = bridge.interceptor(turn, disableParallel = false)),
    )

    private inner class RecoveryCase(
        private val events: Int,
        private val cuts: Int,
        private val streamed: Boolean,
        private val secondScript: Boolean = false,
    ) {
        val posted = mutableListOf<String>()
        val original = JsonObject(
            body + mapOf(
                "input" to JsonArray(
                    body.getValue("input").jsonArray + event("""{"role":"user","content":"stable request"}"""),
                ),
                "prompt_cache_key" to kotlinx.serialization.json.JsonPrimitive("synthetic-cache-key"),
            ),
        )
        val controller = ResponsesReanchorController(
            { ReasoningReplay.decodeReasoningEnvelope(it) },
            maxContinuations = cuts,
        )
        private var failure: TurnOutcome.Failure? = null

        suspend fun post(request: RoundBody, target: WireSink): TurnOutcome {
            if (posted.size > cuts) {
                val input = event(posted.last()).getValue("input").jsonArray
                val continued = event(request.text).getValue("input").jsonArray
                assertEquals(
                    input,
                    JsonArray(continued.take(input.size)),
                    "the recovery input prefix stays byte-identical",
                )
                assertEquals(ResponsesAssistantText.item("suffix ", AssistantPhase.COMMENTARY), continued[input.size])
                assertEquals(outer("reanchored").customCalls.single().raw, continued[input.size + 1])
                posted += request.text
                if (secondScript) {
                    target.addTextBlock("second suffix ")
                    return outer("second").copy(bodyText = "second suffix ", emittedText = true)
                }
                return TurnOutcome.Success(false, false, Usage(), messageClosed = true)
            }
            if (posted.isNotEmpty()) {
                val expected = controller.continuationForFailure(
                    ReanchorRound(event(posted.last()), checkNotNull(failure), posted.size - 1),
                )
                assertArrayEquals(RoundBody.Tree(checkNotNull(expected)).bytes(), request.bytes())
            }
            assertEquals("synthetic-cache-key", JsonScalars.str(event(request.text), "prompt_cache_key"))
            posted += request.text
            return if (posted.size <= cuts) {
                tornRound(events, target).also { failure = it as TurnOutcome.Failure }
            } else {
                recoveredRound(streamed, target)
            }
        }

        fun privateMarker(): kotlinx.serialization.json.JsonElement {
            val recovered = event(posted[cuts]).getValue("input").jsonArray
            assertTrue(
                recovered.size > original.getValue("input").jsonArray.size,
                "this fixture must take marker continuation, not a clean-slate restart: $recovered",
            )
            return recovered.last()
        }

        fun nextRequest(received: List<JsonObject>, changedBaseline: Boolean): JsonObject {
            val baseline = original.getValue("input").jsonArray.toMutableList()
            if (changedBaseline) baseline[1] = event("""{"role":"user","content":"edited request"}""")
            val output = buildJsonObject {
                put("type", "function_call_output")
                put("call_id", JsonScalars.str(received.last(), "call_id"))
                put("output", "read")
            }
            return JsonObject(original + ("input" to JsonArray(baseline + received + output)))
        }
    }

    private suspend fun tornRound(events: Int, sink: WireSink): TurnOutcome {
        // Zero text events covers a tear after an opaque reasoning item, before any prose.
        val frames = if (events == 0) {
            listOf(
                event(
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"synthetic","encrypted_content":"synthetic"}}""",
                ),
            )
        } else {
            List(events) { event("""{"type":"response.output_text.delta","output_index":0,"delta":"prefix "}""") }
        }
        return translator().driveTurn(frames.asFlow(), sink)
    }

    private suspend fun recoveredRound(streamed: Boolean, sink: WireSink): TurnOutcome {
        val call = outer("reanchored").customCalls.single()
        if (!streamed) {
            sink.addTextBlock("suffix ")
            return outer("reanchored").copy(bodyText = "suffix ", emittedText = true)
        }
        val frames = listOf(
            event("""{"type":"response.output_text.delta","output_index":0,"delta":"suffix "}"""),
            event("""{"type":"response.output_item.added","output_index":1,"item":${call.raw}}"""),
            event("""{"type":"response.output_item.done","output_index":1,"item":${call.raw}}"""),
            event("""{"type":"response.completed","response":{"status":"completed","output":[${call.raw}]}}"""),
        )
        return translator().driveTurn(frames.asFlow(), sink)
    }

    /** Only actual Anthropic frames supply the next request, never the upstream marker or its request. */
    private fun clientHistory(frames: List<String>): List<JsonObject> {
        val prose = StringBuilder()
        var callback: JsonObject? = null
        frames.forEach { frame ->
            val data = frame.lineSequence().firstOrNull { it.startsWith("data: ") } ?: return@forEach
            val value = event(data.removePrefix("data: "))
            val delta = value["delta"] as? JsonObject
            if (JsonScalars.str(delta, "type") == "text_delta") prose.append(JsonScalars.str(delta, "text"))
            val block = value["content_block"] as? JsonObject
            if (JsonScalars.str(block, "type") == "tool_use") {
                callback = buildJsonObject {
                    put("type", "function_call")
                    put("call_id", JsonScalars.str(block, "id"))
                    put("name", JsonScalars.str(block, "name"))
                    put("arguments", "{}")
                }
            }
        }
        return listOf(ResponsesAssistantText.item(prose.toString(), AssistantPhase.COMMENTARY), checkNotNull(callback))
    }

    private fun translator() = ResponsesStreamTranslator(
        StreamTurnContext(
            compact = false,
            emitEncryptedReasoning = EmitEncryptedReasoning(false),
            encodeReasoningEnvelope = ReasoningReplay::encodeReasoningEnvelope,
            clientGone = { false },
            watchdogFired = { null },
            streamIdleMsForMessage = 180_000,
            upstreamTimeoutMsForMessage = 900_000,
            summaryPartsShared = SharedSummaryParts(),
            collectReasoningEnvelopes = true,
        ),
    )

    private fun event(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private class PendingRuntime(
        private val completeFirst: Boolean = false,
        private val parkSecond: Boolean = false,
    ) : CodeModeRuntime {
        var starts = 0
        var advances = 0
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            starts++
            return object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                    val park = when (++advances) {
                        1 -> !completeFirst
                        2 -> parkSecond
                        else -> false
                    }
                    return if (park) {
                        CodeModeStep.Calls(listOf(CodeModeCall("read", "Read", buildJsonObject { })))
                    } else {
                        CodeModeStep.Completed("done")
                    }
                }
                override fun close() = Unit
            }
        }
        override fun close() = Unit
    }

    private fun strategy(
        runtime: Runtime,
        finish: suspend (TurnOutcome) -> Unit,
        post: suspend (String) -> TurnOutcome,
    ): RoundStrategy {
        val state = CodeModeStateLocation(tempDir.resolve("code-mode"), tempDir.resolve("state.json"))
        val bridge = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, state))
        val turn = CodexCodeModeBridge.Turn("session", "conversation", "gpt-6-astra", setOf("Read"), emptyList())
        val emitter = SseEmitterFactory().create({}, "model", { buildJsonObject { } })
        return RoundStrategy(
            key = "test",
            log = {},
            emitter = emitter,
            signals = RunnerSignals(watchdogFired = { false }, clientGone = { false }),
            postRoundToSink = { request, _ -> post(request.text) },
            postRound = { post(it.text) },
            finish = { finish(it) },
            interception = RoundInterception(interceptor = bridge.interceptor(turn, disableParallel = false)),
        )
    }

    private fun outer(id: String): TurnOutcome.Success {
        val raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"$id","name":"splice_exec","input":"source"}""",
        ).jsonObject
        return TurnOutcome.Success(
            false,
            false,
            Usage(19, 7, 5, 3),
            bodyText = "visible prose",
            emittedText = true,
            customCalls = listOf(GatewayCustomCall(id, "splice_exec", "source", raw)),
        )
    }

    private val body = Json.parseToJsonElement(
        """{"model":"gpt-6-astra","store":false,"stream":true,"input":[{"role":"developer","content":"s"}]}""",
    ).jsonObject

    private class Runtime(private val fail: Boolean) : CodeModeRuntime {
        var starts = 0
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            starts++
            if (fail) throw IOException("synthetic startup failure")
            return object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                    CodeModeStep.Completed("done")
                override fun close() = Unit
            }
        }
        override fun close() = Unit
    }
}
