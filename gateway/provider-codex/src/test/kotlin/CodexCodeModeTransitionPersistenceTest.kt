// NEW: failed post-runtime persistence retries captured transitions, never worker execution.
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeCell
import splice.spi.CodeModeResult
import splice.spi.CodeModeRuntime
import splice.spi.CodeModeStep
import java.nio.file.Files
import java.nio.file.Path

class CodexCodeModeTransitionPersistenceTest : CodeModeBridgeTestSupport() {
    @Test
    fun `initial calls save failure retries captured callback without rerunning worker`() = runTest {
        val state = tempDir.resolve("bridge.json")
        val runtime = FailingSaveRuntime(
            state,
            listOf(CodeModeStep.Calls(listOf(call("read", "Read")))),
            failAt = 1,
        )
        val manager = bridge(runtime)
        val failedSink = RecordingSink()
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, failedSink) { outerOutcome() }
        assertPersistenceFailure(failed)
        assertTrue(failedSink.tools.isEmpty())
        assertEquals(1, runtime.starts)
        assertEquals(listOf(emptyList<CodeModeResult>()), runtime.cell.results)
        assertFalse(runtime.cell.closed)

        Files.delete(state)
        val retriedSink = RecordingSink()
        val retried = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, retriedSink) { error("retry must not post upstream") }
        assertTrue(retried is TurnOutcome.Success)
        assertTrue((retried as TurnOutcome.Success).hasToolUse)
        assertEquals("Read", retriedSink.tools.single().name)
        assertEquals(1, runtime.starts)
        assertEquals(1, runtime.cell.results.size)
        assertFalse(runtime.cell.closed)
        manager.onHeadStop()
    }

    @Test
    fun `completed save failure retries exact output without rerunning worker`() = runTest {
        val state = tempDir.resolve("bridge.json")
        val output = "completed with \"quotes\"\nand Unicode é"
        val runtime = FailingSaveRuntime(state, listOf(CodeModeStep.Completed(output)), failAt = 1)
        val manager = bridge(runtime)
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        assertPersistenceFailure(failed)
        assertEquals(1, runtime.starts)
        assertEquals(listOf(emptyList<CodeModeResult>()), runtime.cell.results)
        assertTrue(runtime.cell.closed)

        Files.delete(state)
        var upstreamBody = ""
        var posts = 0
        val retriedSink = RecordingSink()
        val retried = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, retriedSink) {
                posts++
                upstreamBody = it
                completedOutcome()
            }
        assertTrue(retried is TurnOutcome.Success)
        assertFalse((retried as TurnOutcome.Success).hasToolUse)
        assertTrue(retriedSink.tools.isEmpty())
        assertEquals(1, posts)
        val items = Json.parseToJsonElement(upstreamBody).jsonObject.getValue("input").jsonArray
        val completed = items.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
            .jsonObject
        assertEquals(output, completed.getValue("output").jsonPrimitive.content)
        assertEquals("outer-call", completed.getValue("call_id").jsonPrimitive.content)
        assertEquals(1, runtime.starts)
        assertEquals(1, runtime.cell.results.size)
        assertTrue(runtime.cell.closed)
    }

    @Test
    fun `followup calls save failure retries new callback without redelivering accepted results`() = runTest {
        val state = tempDir.resolve("bridge.json")
        val runtime = FailingSaveRuntime(
            state,
            listOf(
                CodeModeStep.Calls(listOf(call("read", "Read"))),
                CodeModeStep.Calls(listOf(call("edit", "Edit"))),
            ),
            failAt = 2,
        )
        val manager = bridge(runtime)
        val firstSink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, firstSink) { outerOutcome() }
        val firstId = firstSink.tools.single().id
        val failedSink = RecordingSink()
        val failed = manager.interceptor(turn(firstId, "original result"), disableParallel = false)
            .intercept(requestWithResult(firstId, "original result"), failedSink) { error("must not post") }
        assertPersistenceFailure(failed)
        assertTrue(failedSink.tools.isEmpty())
        val delivered = listOf(emptyList(), listOf(CodeModeResult("read", "original result")))
        assertEquals(delivered, runtime.cell.results)
        assertFalse(runtime.cell.closed)

        Files.delete(state)
        val retriedSink = RecordingSink()
        val retried = manager.interceptor(turn(firstId, "original result"), disableParallel = false)
            .intercept(requestWithResult(firstId, "original result"), retriedSink) { error("must not post") }
        assertTrue(retried is TurnOutcome.Success)
        assertTrue((retried as TurnOutcome.Success).hasToolUse)
        assertEquals("Edit", retriedSink.tools.single().name)
        assertFalse(firstId == retriedSink.tools.single().id)
        assertEquals(delivered, runtime.cell.results)
        assertEquals(1, runtime.starts)
        assertFalse(runtime.cell.closed)
        manager.onHeadStop()
    }

    private fun assertPersistenceFailure(outcome: TurnOutcome) {
        assertTrue(outcome is TurnOutcome.Failure)
        assertTrue((outcome as TurnOutcome.Failure).message.contains("persist"), outcome.message)
    }

    private class FailingSaveRuntime(
        private val state: Path,
        private val steps: List<CodeModeStep>,
        private val failAt: Int,
    ) : CodeModeRuntime {
        var starts = 0
        val cell = RecordingCell()

        override suspend fun start(source: String, tools: Set<String>): CodeModeCell {
            starts++
            return cell
        }

        override fun close() = cell.close()

        inner class RecordingCell : CodeModeCell {
            val results = mutableListOf<List<CodeModeResult>>()
            var closed = false

            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                this.results += results.toList()
                val step = steps[this.results.lastIndex]
                if (this.results.size == failAt) {
                    Files.delete(state)
                    Files.createDirectory(state)
                }
                return step
            }

            override fun close() {
                closed = true
            }
        }
    }
}
