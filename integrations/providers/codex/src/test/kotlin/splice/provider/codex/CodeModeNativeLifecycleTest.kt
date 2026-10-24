package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.provider.codex.stream.CodeModeNativeInvocations
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions

class CodeModeNativeLifecycleTest : CodeModeBridgeTestSupport() {
    @Test
    fun `exec yield and wait extend exact model history with only new native output`() = runTest {
        val cell =
            NativeCell(ArrayDeque(listOf(CodeModeStep.Yielded("stable", "first"), CodeModeStep.Completed("second"))))
        val runtime = NativeRuntime(ArrayDeque(listOf(cell)))
        val bridge = bridge(runtime)
        val posts = mutableListOf<JsonArray>()
        val replies =
            ArrayDeque(listOf(outcome(outer("exec")), outcome(waitCall("wait", "stable")), completedOutcome()))
        val result = bridge.interceptor(
            turn(),
            disableParallel = false,
        ).intercept(BASE_REQUEST, RecordingSink()) { body ->
            posts += Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            RoundResult.Outcome(replies.removeFirst())
        }.turn()
        assertTrue(result is TurnOutcome.Success)
        val final = posts.last().filterIsInstance<JsonObject>()
        assertEquals(
            listOf("custom_tool_call", "function_call"),
            final.mapNotNull {
                JsonScalars.str(it, "type").takeIf { kind -> kind in setOf("custom_tool_call", "function_call") }
            },
        )
        val outputs = final.filter {
            JsonScalars.str(
                it,
                "type",
            ) in setOf("custom_tool_call_output", "function_call_output")
        }
        assertTrue(outputs.first().toString().contains("Script running with cell ID stable"))
        assertTrue(outputs.last().toString().contains("second"))
        assertFalse(outputs.last().toString().contains("first"))
        assertTrue(cell.closed)
        bridge.onHeadStop()
    }

    @Test
    fun `terminating a yielded cell returns native termination without executing wait as JavaScript`() = runTest {
        val cell = NativeCell(ArrayDeque(listOf(CodeModeStep.Yielded("stable", "first"))))
        val runtime = NativeRuntime(ArrayDeque(listOf(cell)))
        val bridge = bridge(runtime)
        var finalInput = ""
        val replies =
            ArrayDeque(listOf(outcome(outer("exec")), outcome(waitCall("stop", "stable", true)), completedOutcome()))
        val result = bridge.interceptor(
            turn(),
            disableParallel = false,
        ).intercept(BASE_REQUEST, RecordingSink()) { body ->
            finalInput = body
            RoundResult.Outcome(replies.removeFirst())
        }.turn()
        assertTrue(result is TurnOutcome.Success)
        assertTrue(finalInput.contains("Script terminated"))
        assertTrue(cell.closed)
        bridge.onHeadStop()
    }

    @Test
    fun `native notifications remain exact canonical tool outputs before final execution output`() = runTest {
        val cell = NativeCell(ArrayDeque(listOf(CodeModeStep.Notified("progress"), CodeModeStep.Completed("done"))))
        val runtime = NativeRuntime(ArrayDeque(listOf(cell)))
        val bridge = bridge(runtime)
        var finalInput = JsonArray(emptyList())
        val replies = ArrayDeque(listOf(outcome(outer("exec")), completedOutcome()))
        bridge.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) { body ->
            finalInput = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            RoundResult.Outcome(replies.removeFirst())
        }.turn()
        val outputs = finalInput.filterIsInstance<JsonObject>().filter {
            JsonScalars.str(it, "type") == "custom_tool_call_output"
        }
        assertEquals("progress", JsonScalars.str(outputs.first(), "output"))
        assertEquals("exec", JsonScalars.str(outputs.first(), "call_id"))
        assertTrue(outputs.last().toString().contains("Script completed"))
        assertFalse(
            finalInput.any {
                (it as? JsonObject)?.let { item -> JsonScalars.str(item, "role") == "assistant" } == true
            },
        )
        bridge.onHeadStop()
    }

    @Test
    fun `native invocation options preserve explicit observation and output budgets`() {
        val invocation = CodeModeNativeInvocations.exec(
            "// @exec: {\"yield_time_ms\":7,\"max_output_tokens\":3}\ntext(1)",
        )
        assertEquals(NativeCodeModeOptions(7, 3), invocation.options)
        val waiting = CodeModeNativeInvocations.wait(
            """{"cell_id":"stable","yield_time_ms":9,"max_tokens":2,"terminate":true}""",
        )
        assertEquals(NativeCodeModeOptions(9, 2), waiting.options)
        assertTrue(waiting.terminate)
    }

    @Test
    fun `invalid native directives are refused before executable source is dispatched`() {
        val invalid = listOf(
            "// @exec: {}",
            "// @exec: {\"other\":1}\ntext(1)",
            "// @exec: {\"yield_time_ms\":\"1\"}\ntext(1)",
            "// @exec: {\"yield_time_ms\":-1}\ntext(1)",
            "// @exec: {\"max_output_tokens\":1.5}\ntext(1)",
        )
        invalid.forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { CodeModeNativeInvocations.exec(source) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CodeModeNativeInvocations.wait("""{"cell_id":"stable","terminate":"true"}""")
        }
        val parsed = CodeModeNativeInvocations.exec("// @exec: {}\ntext(1)")
        assertEquals("text(1)", parsed.source)
    }

    private fun waitCall(id: String, cell: String, terminate: Boolean = false): GatewayCustomCall {
        val args = buildJsonObject { put("cell_id", cell); put("terminate", terminate) }.toString()
        return GatewayCustomCall(
            id,
            "wait",
            args,
            buildJsonObject {
                put("type", "function_call")
                put("call_id", id)
                put("name", "wait")
                put("arguments", args)
            },
        )
    }

    private fun outcome(call: GatewayCustomCall) = TurnOutcome.Success(
        false,
        false,
        Usage(),
        handoffs = RoundHandoffs(customCalls = listOf(call)),
    )

    private class NativeRuntime(private val cells: ArrayDeque<NativeCell>) : NativeCodeModeLifecycle {
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell =
            error("native lifecycle must be used")

        override suspend fun startNativeSession(
            sessionKey: String,
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
            options: NativeCodeModeOptions,
        ): NativeCodeModeCell {
            return cells.removeFirst()
        }

        override fun close() = Unit
    }

    private class NativeCell(private val steps: ArrayDeque<CodeModeStep>) : NativeCodeModeCell {
        override val cellId = "stable"
        var closed = false
        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = observe(0)
        override suspend fun submitResults(results: List<CodeModeResult>) = check(!closed)
        override suspend fun observe(yieldTimeMs: Long): CodeModeStep = steps.removeFirst()
        override suspend fun terminate(): CodeModeStep {
            closed = true
            return CodeModeStep.Completed("", terminated = true)
        }
        override fun close() { closed = true }
    }
}
