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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.sse.WireSink

/** Captured real-client structure: notify in exec, yield, wait issues a callback, then callback-only client replay. */
class CodeModeNotifyWaitReplayTest : CodeModeBridgeTestSupport() {
    @Test
    fun `prior exec progress belongs to the wait callback replay and preserves native suffix media`() = runTest {
        replay("native-progress", "")
    }

    @Test
    fun `coalesced exec progress and wait preface preserve callback ownership and native suffix media`() = runTest {
        replay("native-progresswait-preface", "wait-preface")
    }

    private suspend fun replay(clientCopy: String, waitPreface: String) {
        val content = nativeContent()
        val cell = nativeCell(content)
        val bridge = bridge(Host(cell))
        val calls = RecordingSink()
        val progress = mutableListOf<String>()
        val sink = object : WireSink by calls {
            override suspend fun addTextBlock(text: String) { progress += text }
        }
        val initial = ArrayDeque(listOf(outcome(outer("exec")), outcome(waitCall(), waitPreface)))
        val issued = bridge.interceptor(turn(), disableParallel = false).intercept(REQUEST, sink) {
            RoundResult.Outcome(initial.removeFirst())
        }.turn()
        assertTrue(issued is TurnOutcome.Success && issued.hasToolUse)
        assertEquals(listOf("native-progress"), progress)
        val id = calls.tools.single().id
        val followup = """{"input":[{"role":"developer","content":"s"},{"role":"user","content":"go"},""" +
            """{"role":"assistant","content":"$clientCopy"},""" +
            """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"$id","output":"ok"}]}"""
        var finalInput = JsonArray(emptyList())
        val resumed = bridge.interceptor(
            turn(id, "ok"),
            disableParallel = false,
        ).intercept(followup, RecordingSink()) { body ->
            finalInput = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            RoundResult.Outcome(completedOutcome())
        }.turn()
        assertTrue(resumed is TurnOutcome.Success)
        val output = finalInput.filterIsInstance<JsonObject>().single {
            JsonScalars.str(it, "type") == "function_call_output" && JsonScalars.str(it, "call_id") == "wait"
        }.getValue("output")
        assertTrue(output is JsonArray, "native wait media became text or synthetic termination")
        val received = (output as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        assertTrue(received.containsAll(content))
        assertFalse(output.toString().contains("Script terminated"))
        assertFalse(logLines.any { it.contains("interrupted extra=STEERING") })
        bridge.onHeadStop()
    }

    private fun nativeContent(): List<JsonObject> = listOf(
        buildJsonObject { put("type", "input_text"); put("text", "native-suffix") },
        buildJsonObject {
            put("type", "input_image"); put("image_url", "data:image/png;base64,AA=="); put("detail", "original")
        },
        buildJsonObject { put("type", "input_audio"); put("data", "AA=="); put("format", "wav") },
    )

    private fun nativeCell(content: List<JsonObject>): Cell = Cell(
        ArrayDeque(
            listOf(
                CodeModeStep.Notified("native-progress"),
                CodeModeStep.Yielded("stable", "native-prefix"),
                CodeModeStep.Calls(listOf(CodeModeCall("read", "Read", JsonObject(emptyMap())))),
                CodeModeStep.Completed("native-suffix", content = content),
            ),
        ),
    )

    private fun waitCall(): GatewayCustomCall {
        val args = """{"cell_id":"stable"}"""
        return GatewayCustomCall(
            "wait",
            "wait",
            args,
            buildJsonObject {
                put("type", "function_call"); put("call_id", "wait"); put("name", "wait"); put("arguments", args)
            },
        )
    }

    private fun outcome(call: GatewayCustomCall, preface: String = ""): TurnOutcome.Success = TurnOutcome.Success(
        false,
        false,
        Usage(),
        text = RoundText(bodyText = preface, emittedText = preface.isNotEmpty()),
        handoffs = RoundHandoffs(customCalls = listOf(call)),
    )

    private class Host(private val cell: Cell) : NativeCodeModeLifecycle {
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
        ): NativeCodeModeCell = cell
        override fun close() = Unit
    }

    private class Cell(private val steps: ArrayDeque<CodeModeStep>) : NativeCodeModeCell {
        override val cellId: String = "stable"
        override suspend fun submitResults(results: List<CodeModeResult>) = Unit
        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = observe(0)
        override suspend fun observe(yieldTimeMs: Long): CodeModeStep = steps.removeFirst()
        override suspend fun terminate(): CodeModeStep = CodeModeStep.Completed("", terminated = true)
        override fun close() = Unit
    }
}

private const val REQUEST = """{"input":[{"role":"developer","content":"s"},{"role":"user","content":"go"}]}"""
