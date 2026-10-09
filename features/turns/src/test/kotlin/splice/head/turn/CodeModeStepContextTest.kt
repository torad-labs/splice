// NEW: a code-mode step with no upstream round of its own reports the conversation's last measured
// context to Claude Code, which reads every assistant message's usage as the context total.
package splice.head.turn

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.GatewayCustomCall
import splice.core.turn.ReasoningDisplay
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.Usage
import splice.head.compact.CompactStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RoundInterception
import splice.head.round.RoundRunners
import splice.head.round.RoundStrategy
import splice.head.round.RunnerSignals
import splice.head.wire.SseEmitterFactory
import splice.head.wire.TurnWiring
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Path

private const val MODEL = "gpt-6-astra"
private val CONTEXT_FIELDS = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")

class CodeModeStepContextTest {
    @TempDir
    lateinit var tempDir: Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(ModelEntry(MODEL, "Astra", contextWindow = 272_000)),
        defaultContextWindow = 272_000,
    )
    private val wiring = TurnWiring(log = {})
    private val pipeline by lazy { TurnPipeline(CompactStats(tempDir.resolve("compact.jsonl")), {}, { it }) }

    /** One client turn through the head's own round strategy, stream pipeline and usage payload. */
    private inner class ClientTurn(private val bridge: CodexCodeModeBridge, results: List<CodeModeResult>) {
        val frames = mutableListOf<String>()
        var outcome: TurnOutcome? = null
        private val turn = CodexCodeModeBridge.Turn("session", "conversation", MODEL, setOf("Read"), results)

        suspend fun run(body: JsonObject, post: suspend () -> TurnOutcome) {
            val usage = wiring.usagePayloadBuilderFor(catalog, MODEL)
            val emitter = SseEmitterFactory().create({ frames += it }, MODEL, usage)
            RoundStrategy(
                emitter = emitter,
                runners = RoundRunners(
                    key = "test",
                    log = {},
                    signals = RunnerSignals(watchdogFired = { false }, clientGone = { false }),
                    finish = { finished ->
                        outcome = finished
                        pipeline.finishStream(emitter, finished, meta, 0)
                    },
                ),
                postRoundToSink = { _, _ -> RoundResult.Outcome(post()) },
                postRound = { RoundResult.Outcome(post()) },
                interception = RoundInterception(interceptor = bridge.interceptor(turn, disableParallel = false)),
            ).run(body, null, null)
        }

        fun usage(): JsonObject {
            val delta = frames.single { it.startsWith("event: message_delta") }
            val data = delta.lineSequence().first { it.startsWith("data: ") }.removePrefix("data: ")
            return Json.parseToJsonElement(data).jsonObject.getValue("usage").jsonObject
        }

        fun context(): Map<String, Long> = CONTEXT_FIELDS.associateWith { usage().getValue(it).jsonPrimitive.long }

        fun toolId(): String = frames.filter { it.startsWith("event: content_block_start") }
            .map { Json.parseToJsonElement(it.substringAfter("data: ")).jsonObject }
            .map { it.getValue("content_block").jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "tool_use" }
            .getValue("id").jsonPrimitive.content
    }

    @Test
    fun `a local step after a measured round reports that round's context, and bills nothing`() = runTest {
        val bridge = bridge(listOf(calls("a"), calls("b"), CodeModeStep.Completed("done")))
        try {
            val first = ClientTurn(bridge, emptyList())
            first.run(request(START)) { scriptRound(Usage(inputTokens = 1_000, outputTokens = 7, cachedTokens = 600)) }
            val measured = first.context()
            assertTrue(measured.values.sum() > 0, "the round's own context reaches the client: $measured")

            val id = first.toolId()
            val step = ClientTurn(bridge, listOf(CodeModeResult(id, "A")))
            step.run(request("$START,${callback(id)}")) { error("a local step posts nothing upstream") }

            assertEquals(measured, step.context(), "the step reports the conversation's last measured context")
            assertEquals(0L, step.usage().getValue("output_tokens").jsonPrimitive.long, "a step generated nothing")
            val raw = (step.outcome as TurnOutcome.Success).usage
            assertTrue(raw.origin.localStep, "the step is local")
            assertEquals(0L, raw.inputTokens + raw.cachedTokens + raw.cacheWriteTokens + raw.outputTokens, "billed")
        } finally {
            bridge.onHeadStop()
        }
    }

    @Test
    fun `a step before any round of the conversation reported stays zero`() = runTest {
        val bridge = bridge(listOf(calls("a"), calls("b"), CodeModeStep.Completed("done")))
        try {
            val first = ClientTurn(bridge, emptyList())
            first.run(request(START)) { scriptRound(Usage(outputTokens = 7)) }
            val id = first.toolId()
            val step = ClientTurn(bridge, listOf(CodeModeResult(id, "A")))
            step.run(request("$START,${callback(id)}")) { error("a local step posts nothing upstream") }
            assertEquals(CONTEXT_FIELDS.associateWith { 0L }, step.context(), "nothing was ever measured")
        } finally {
            bridge.onHeadStop()
        }
    }

    private val meta = TurnMeta(
        compact = false,
        reasoning = TurnReasoning(
            showReasoning = ReasoningDisplay.TEXT,
            effort = "medium",
            summary = null,
            budgetTokens = null,
        ),
        route = TurnRoute(stream = true, originalModel = MODEL, upstreamModel = MODEL, clientMaxTokens = null),
    )

    private fun bridge(steps: List<CodeModeStep>): CodexCodeModeBridge {
        val state = CodeModeStateLocation(tempDir.resolve("code-mode"), tempDir.resolve("state.json"))
        return CodexCodeModeBridge(CodeModeBridgeConfig({ Runtime(ArrayDeque(steps)) }, state))
    }

    private fun calls(id: String) = CodeModeStep.Calls(listOf(CodeModeCall(id, "Read", buildJsonObject { })))

    /** The upstream round that writes the script: one code-mode call, with its usage. */
    private fun scriptRound(usage: Usage): TurnOutcome.Success {
        val name = CodeModeManual.TOOL_NAME
        val raw = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"outer-1","name":"$name","input":"source"}""",
        ).jsonObject
        return TurnOutcome.Success(
            false,
            false,
            usage,
            handoffs = RoundHandoffs(customCalls = listOf(GatewayCustomCall("outer-1", name, "source", raw))),
        )
    }

    private fun request(items: String): JsonObject = Json.parseToJsonElement(
        """{"model":"$MODEL","store":false,"stream":true,"input":[{"role":"developer","content":"s"},$items]}""",
    ).jsonObject

    private fun callback(id: String): String =
        """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"$id","output":"A"}"""

    private class Runtime(private val steps: ArrayDeque<CodeModeStep>) : CodeModeRuntime {
        override suspend fun start(source: String, tools: Set<String>, descriptions: Map<String, String>) =
            object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = steps.removeFirst()
                override fun close() = Unit
            }

        override fun close() = Unit
    }
}

private const val START = """{"role":"user","content":"start"}"""
