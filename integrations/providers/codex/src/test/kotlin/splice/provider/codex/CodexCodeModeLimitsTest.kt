package splice.provider.codex

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Files

class CodexCodeModeLimitsTest : CodeModeBridgeTestSupport() {
    @Test
    fun `oversized UTF8 source is rejected before runtime admission`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("done"))))
        val outcome = bridge(runtime).interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) {
                outerOutcome().copy(customCalls = listOf(outer(source = "é".repeat(32_769))))
            }
        assertTrue(outcome is TurnOutcome.Failure)
        assertEquals(0, runtime.starts)
        assertFalse(Files.exists(tempDir.resolve("bridge.json")))
    }

    @Test
    fun `oversized UTF8 result is admitted truncated with the marker and the turn completes`() = runTest {
        val steps = listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("done"))
        val runtime = ScriptedRuntime(ArrayDeque(steps))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        val id = sink.tools.single().id
        val oversized = "é".repeat(35_840) // 70 KiB: the Read output Claude Code cannot shrink on request
        val completed = manager.interceptor(turn(id, oversized), disableParallel = false)
            .intercept(requestWithResult(id, oversized), RecordingSink()) { completedOutcome() }
        assertTrue(completed is TurnOutcome.Success, completed.toString())
        val admitted = runtime.cell.results.last().single().output
        assertTrue(admitted.encodeToByteArray().size <= CodeModeLimits.MAX_TEXT_BYTES)
        assertTrue(admitted.contains(" [truncated ") && admitted.endsWith(" chars]"), admitted.takeLast(40))
        assertTrue(oversized.startsWith(admitted.substringBefore(" [truncated ")))
        assertEquals(2, runtime.cell.advances)
    }

    @Test
    fun `a result at the exact byte boundary is admitted untouched`() = runTest {
        val steps = listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("done"))
        val runtime = ScriptedRuntime(ArrayDeque(steps))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        val id = sink.tools.single().id
        val boundary = "é".repeat(32_768)
        val completed = manager.interceptor(turn(id, boundary), disableParallel = false)
            .intercept(requestWithResult(id, boundary), RecordingSink()) { completedOutcome() }
        assertTrue(completed is TurnOutcome.Success)
        assertEquals(boundary, runtime.cell.results.last().single().output)
    }
}
