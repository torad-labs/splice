package splice.provider.codex.v4388

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.provider.codex.BASE_REQUEST
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodeModeExecOutput
import splice.provider.codex.turn
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeStep

/** V4-388: an exec output reads as codex frames it (codex-rs core/src/tools/code_mode/output.rs:31 and
 *  mod.rs:283): status, wall time, "Output:", the script's text, and "Script error:" last on a failure. */
class CodeModeExecOutputTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a completed script carries codex's header over its output`() {
        assertEquals(
            "Script completed\nWall time 1.3 seconds\nOutput:\nalpha\nbeta",
            CodeModeExecOutput.completed("alpha\nbeta", 1_250, BUDGET),
        )
    }

    @Test
    fun `a failed script shows what it logged, then the error codex's way`() {
        assertEquals(
            "Script failed\nWall time 0.4 seconds\nOutput:\nbefore\nScript error:\nError: ENOENT",
            CodeModeExecOutput.failed("before", "Error: ENOENT", 400, BUDGET),
        )
        assertEquals(
            "Script failed\nWall time 0.0 seconds\nOutput:\nScript error:\nSyntaxError: Unexpected token",
            CodeModeExecOutput.failed("", "SyntaxError: Unexpected token", 0, BUDGET),
        )
    }

    @Test
    fun `a full worker output stays whole under its header, and only the upstream ceiling cuts it`() {
        val full = "x".repeat(CodeModeLimits.MAX_TEXT_BYTES)
        assertEquals(
            "Script completed\nWall time 0.0 seconds\nOutput:\n$full",
            CodeModeExecOutput.completed(full, 10, BUDGET),
        )
        val small = CodeModeExecOutput.failed(full, "Error: late", 10, SMALL_BUDGET)
        assertTrue(small.length in SMALL_BUDGET - MARKER_SLACK..SMALL_BUDGET, "${small.length} chars")
        assertTrue(small.contains("[truncated "), small.takeLast(80))
        assertTrue(small.endsWith("\nScript error:\nError: late"), small.takeLast(80))
    }

    @Test
    fun `a bridge reports elapsed time from its explicit mutable clock`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("read", "Read"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime, clock = clock)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { RoundResult.Outcome(outerOutcome()) }
        val resultId = sink.tools.single().id
        clock.now += 1_200

        var upstream = ""
        val outcome = manager.interceptor(turn(resultId, "read answer"), disableParallel = false)
            .intercept(requestWithResult(resultId, "read answer"), RecordingSink()) {
                upstream = it
                RoundResult.Outcome(completedOutcome())
            }.turn()

        assertTrue(outcome is TurnOutcome.Success)
        val output = Json.parseToJsonElement(upstream).jsonObject.getValue("input").jsonArray
            .map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "custom_tool_call_output" }
            .getValue("output").jsonPrimitive.content
        assertEquals("Script completed\nWall time 1.2 seconds\nOutput:\ndone", output)
        manager.onHeadStop()
    }

    private companion object {
        const val BUDGET = 1_048_576
        const val SMALL_BUDGET = 4_096
        const val MARKER_SLACK = 8
    }
}
