package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep

class CodexCodeModeAdmissionTest : CodeModeBridgeTestSupport() {
    @Test
    fun `failed initial admission can retry before any execution`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("done"))))
        val manager = bridge(runtime)
        stateFiles.block()
        val failed = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            RoundResult.Outcome(outerOutcome())
        }.turn()
        assertTrue(failed is TurnOutcome.Failure)
        assertEquals(0, runtime.starts)
        stateFiles.unblock()
        var posts = 0
        val retried = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            RoundResult.Outcome(if (posts++ == 0) outerOutcome() else completedOutcome())
        }.turn()
        assertTrue(retried is TurnOutcome.Success, retried.toString())
        assertEquals(1, runtime.starts)
        assertEquals(1, runtime.cell.advances)
    }

    @Test
    fun `failed admission at capacity preserves prior completed record and expiry history`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Completed("first"))),
                    ArrayDeque(listOf(CodeModeStep.Completed("second"))),
                ),
            ),
        )
        val manager = bridge(runtime, retention = CodeModeRetention(records = 1))
        var posts = 0
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            RoundResult.Outcome(if (posts++ == 0) outerOutcome() else completedOutcome())
        }
        val before = stateFiles.state()
        stateFiles.block()
        val other = turn(sessionId = "session-b")
        val failed = manager.interceptor(other, disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            RoundResult.Outcome(outerOutcome("second"))
        }.turn()
        assertTrue(failed is TurnOutcome.Failure)
        assertEquals(1, runtime.starts)
        stateFiles.unblock()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            RoundResult.Outcome(completedOutcome())
        }
        val after = stateFiles.state()
        assertEquals(before.getValue("records").jsonArray, after.getValue("records").jsonArray)
        assertEquals(before["expired"], after["expired"])
        assertEquals(1, runtime.starts)
    }
}
