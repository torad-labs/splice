package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions

/** The fake host owns observable session state; record eviction must never reset that host state. */
class CodeModeNativeSessionLifetimeTest : CodeModeBridgeTestSupport() {
    @Test
    fun `session values survive execution record eviction while client liveness is unknown`() = runTest {
        val host = StatefulHost()
        val bridge = bridge(host, retention = CodeModeRetention(perConversation = 1))
        val first = ArrayDeque(listOf(outcome(outer("store", STORE_SOURCE)), completedOutcome()))
        bridge.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            RoundResult.Outcome(first.removeFirst())
        }.turn()
        var input = JsonArray(emptyList())
        val second = ArrayDeque(listOf(outcome(outer("load", LOAD_SOURCE)), completedOutcome()))
        val next = bridge.interceptor(
            turn(model = "gpt-6-sol"),
            disableParallel = false,
        ).intercept(nextRequest(), RecordingSink()) { body ->
            input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            RoundResult.Outcome(second.removeFirst())
        }.turn()
        assertTrue(next is TurnOutcome.Success)
        val output = output(input, "load")
        assertTrue(output.toString().contains("kept"), "record eviction reset the native session store")
        bridge.onHeadStop()
    }

    @Test
    fun `a yielded cell survives another cell admitted under execution record pressure`() = runTest {
        val host = StatefulHost()
        val bridge = bridge(host, retention = CodeModeRetention(perConversation = 1))
        val first = ArrayDeque(listOf(outcome(outer("first", YIELD_SOURCE)), completedOutcome()))
        bridge.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            RoundResult.Outcome(first.removeFirst())
        }.turn()
        var input = JsonArray(emptyList())
        val second = ArrayDeque(
            listOf(
                outcome(outer("second", OTHER_SOURCE)),
                outcome(waitCall()),
                completedOutcome(),
            ),
        )
        val next = bridge.interceptor(
            turn(model = "gpt-6-sol"),
            disableParallel = false,
        ).intercept(nextRequest(), RecordingSink()) { body ->
            input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            RoundResult.Outcome(second.removeFirst())
        }.turn()
        assertTrue(next is TurnOutcome.Success)
        assertTrue(output(input, "wait-first").toString().contains("suffix"))
        bridge.onHeadStop()
        assertTrue(host.held.closed, "head stop did not close the yielded native engine")
    }

    private fun nextRequest(): String = """{"input":[{"role":"developer","content":"s"},""" +
        """{"role":"user","content":"next"}]}"""

    private fun output(input: JsonArray, id: String): JsonObject = input.filterIsInstance<JsonObject>().single {
        JsonScalars.str(
            it,
            "call_id",
        ) == id && JsonScalars.str(it, "type") in OUTPUT_TYPES
    }

    private fun waitCall(): GatewayCustomCall {
        val arguments = """{"cell_id":"first-cell"}"""
        return GatewayCustomCall(
            "wait-first",
            "wait",
            arguments,
            buildJsonObject {
                put(
                    "type",
                    "function_call",
                ); put("call_id", "wait-first"); put("name", "wait"); put("arguments", arguments)
            },
        )
    }

    private fun outcome(call: GatewayCustomCall): TurnOutcome.Success = TurnOutcome.Success(
        false,
        false,
        Usage(),
        handoffs = RoundHandoffs(customCalls = listOf(call)),
    )

    private class StatefulHost : NativeCodeModeLifecycle {
        private val values = mutableMapOf<String, String>()
        val held =
            Cell(
                "first-cell",
                ArrayDeque(listOf(CodeModeStep.Yielded("first-cell", "prefix"), CodeModeStep.Completed("suffix"))),
            )
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell =
            error("Native lifecycle must be used")

        override suspend fun startNativeSession(
            sessionKey: String,
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
            options: NativeCodeModeOptions,
        ): NativeCodeModeCell = when (source) {
            STORE_SOURCE -> {
                values[sessionKey] = "kept"
                Cell("stored-cell", ArrayDeque(listOf(CodeModeStep.Completed(""))))
            }
            LOAD_SOURCE -> Cell(
                "loaded-cell",
                ArrayDeque(listOf(CodeModeStep.Completed(values[sessionKey] ?: "missing"))),
            )
            YIELD_SOURCE -> held
            OTHER_SOURCE -> Cell("other-cell", ArrayDeque(listOf(CodeModeStep.Completed("other"))))
            else -> error("Unexpected source")
        }

        override fun closeSession(sessionKey: String) { values.remove(sessionKey); held.close() }
        override fun close() { values.clear(); held.close() }
    }

    private class Cell(override val cellId: String, private val steps: ArrayDeque<CodeModeStep>) : NativeCodeModeCell {
        var closed = false
        override suspend fun submitResults(results: List<CodeModeResult>) { check(!closed) }
        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = observe(0)
        override suspend fun observe(yieldTimeMs: Long): CodeModeStep { check(!closed); return steps.removeFirst() }
        override suspend fun terminate(): CodeModeStep {
            closed = true; return CodeModeStep.Completed(
                "",
                terminated = true,
            )
        }
        override fun close() { closed = true }
    }
}

private const val STORE_SOURCE = "store('x','kept')"
private const val LOAD_SOURCE = "text(load('x'))"
private const val YIELD_SOURCE = "text('prefix'); await yield_control(); text('suffix')"
private const val OTHER_SOURCE = "text('other')"

private val OUTPUT_TYPES = setOf("custom_tool_call_output", "function_call_output")
