import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeStep
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
    fun `oversized UTF8 result is rejected without consumption and corrected result can resume`() = runTest {
        val steps = listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("done"))
        val runtime = ScriptedRuntime(ArrayDeque(steps))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        val id = sink.tools.single().id
        val oversized = "é".repeat(32_769)
        val before = Files.readString(tempDir.resolve("bridge.json"))
        val rejected = manager.interceptor(turn(id, oversized), disableParallel = false)
            .intercept(requestWithResult(id, oversized), RecordingSink()) { completedOutcome() }
        assertTrue(rejected is TurnOutcome.Failure)
        assertEquals(before, Files.readString(tempDir.resolve("bridge.json")))
        assertEquals(1, runtime.cell.advances)
        val boundary = "é".repeat(32_768)
        val corrected = manager.interceptor(turn(id, boundary), disableParallel = false)
            .intercept(requestWithResult(id, boundary), RecordingSink()) { completedOutcome() }
        assertTrue(corrected is TurnOutcome.Success)
        assertEquals(boundary, runtime.cell.results.last().single().output)
        assertEquals(2, runtime.cell.advances)
    }
}
