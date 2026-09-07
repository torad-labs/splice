import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeStep
import java.nio.file.Files

class CodexCodeModeAdmissionTest : CodeModeBridgeTestSupport() {
    @Test
    fun `failed initial admission can retry before any execution`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("done"))))
        val manager = bridge(runtime)
        val state = tempDir.resolve("bridge.json")
        Files.createDirectory(state)
        val failed = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            outerOutcome()
        }
        assertTrue(failed is TurnOutcome.Failure)
        assertEquals(0, runtime.starts)
        Files.delete(state)
        var posts = 0
        val retried = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            if (posts++ == 0) outerOutcome() else completedOutcome()
        }
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
        val manager = bridge(runtime, maxRecords = 1)
        var posts = 0
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            if (posts++ == 0) outerOutcome() else completedOutcome()
        }
        val state = tempDir.resolve("bridge.json")
        val before = Json.parseToJsonElement(Files.readString(state)).jsonObject
        Files.delete(state)
        Files.createDirectory(state)
        val other = turn(sessionId = "session-b")
        val failed = manager.interceptor(other, disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            outerOutcome("second")
        }
        assertTrue(failed is TurnOutcome.Failure)
        assertEquals(1, runtime.starts)
        Files.delete(state)
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            completedOutcome()
        }
        val after = Json.parseToJsonElement(Files.readString(state)).jsonObject
        assertEquals(before.getValue("records").jsonArray, after.getValue("records").jsonArray)
        assertEquals(before["expired"], after["expired"])
        assertEquals(1, runtime.starts)
    }
}
