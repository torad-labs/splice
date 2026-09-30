// NEW: failed post-runtime persistence retries captured transitions, never worker execution.
package splice.provider.codex

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
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

class CodexCodeModeTransitionPersistenceTest : CodeModeBridgeTestSupport() {
    @Test
    fun `worker failure starts with safe cause and counts accepted results`() = runTest {
        var failureText = "worker pool exhausted\nPRIVATE_TOOL_RESULT"
        val runtime = object : CodeModeRuntime {
            override suspend fun start(
                source: String,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell = object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                    error(failureText)
                override fun close() = Unit
            }
            override fun close() = Unit
        }
        val outcome = bridge(runtime).interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        assertTrue(outcome is TurnOutcome.Failure)
        val message = (outcome as TurnOutcome.Failure).message
        assertTrue(message.contains("IllegalStateException: worker pool exhausted"), message)
        assertTrue(message.contains("accepted results=0"), message)
        assertFalse(message.contains("call ids="), message)
        assertFalse(message.contains("PRIVATE_TOOL_RESULT"), "exception payload must stay private: $message")

        failureText = "PRIVATE_TOOL_RESULT as first line"
        val privateOutcome = bridge(runtime).interceptor(
            turn(sessionId = "other-session"),
            outer("other-call"),
            disableParallel = false,
        ).intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("other-call") }
        assertTrue(privateOutcome is TurnOutcome.Failure)
        val privateMessage = (privateOutcome as TurnOutcome.Failure).message
        assertTrue("IllegalStateException: message withheld" in privateMessage, privateMessage)
        assertFalse("PRIVATE_TOOL_RESULT" in privateMessage, "private worker text must never enter the log")
    }

    @Test
    fun `initial calls save failure retries captured callback without rerunning worker`() = runTest {
        val runtime = FailingSaveRuntime(
            stateFiles,
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

        stateFiles.unblock()
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
        val output = "completed with \"quotes\"\nand Unicode é"
        val runtime = FailingSaveRuntime(stateFiles, listOf(CodeModeStep.Completed(output)), failAt = 1)
        val manager = bridge(runtime)
        val failed = manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        assertPersistenceFailure(failed)
        assertEquals(1, runtime.starts)
        assertEquals(listOf(emptyList<CodeModeResult>()), runtime.cell.results)
        assertTrue(runtime.cell.closed)

        stateFiles.unblock()
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
        // V4-388: the exact script output, under codex's exec header (the test clock does not move).
        assertEquals(
            "Script completed\nWall time 0.0 seconds\nOutput:\n$output",
            completed.getValue("output").jsonPrimitive.content,
        )
        assertEquals("outer-call", completed.getValue("call_id").jsonPrimitive.content)
        assertEquals(1, runtime.starts)
        assertEquals(1, runtime.cell.results.size)
        assertTrue(runtime.cell.closed)
    }

    @Test
    fun `followup calls save failure retries new callback without redelivering accepted results`() = runTest {
        val runtime = FailingSaveRuntime(
            stateFiles,
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

        stateFiles.unblock()
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

    @Test
    fun `failed issued-step save cannot make a later retry serve an unpersisted callback`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))))))
        val manager = bridge(runtime)
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        val config = CodeModeBridgeConfig(
            { runtime },
            stateLocation(),
            clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
        )
        val registry = CodexCodeModeRegistry(config, Json, 5.minutes)
        val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
        val record = registry.recordsFor(key).single()
        assertEquals(1, record.issued.size)
        record.lastDigest = "next-request-digest"
        val machine = CodexCodeModeMachine(config, registry, CodexCodeModeValidation(config))
        stateFiles.block()
        var failed = false
        try {
            machine.emit(record, record.visiblePending(), RecordingSink())
        } catch (_: CodeModePersistenceException) {
            failed = true
        } finally {
            stateFiles.unblock()
        }
        assertTrue(failed, "the issued-step save really failed before any client callback")
        assertEquals(1, record.issued.size, "a failed save cannot leave a replayable in-memory step")
        machine.emit(record, record.visiblePending(), RecordingSink())
        assertEquals(2, registry.recordsFor(key).single().issued.size)
        assertEquals(2, stateFiles.records().single().getValue("issued").jsonArray.size)
    }

    private fun assertPersistenceFailure(outcome: TurnOutcome) {
        assertTrue(outcome is TurnOutcome.Failure)
        assertTrue((outcome as TurnOutcome.Failure).message.contains("could not be saved"), outcome.message)
    }

    private class FailingSaveRuntime(
        private val state: CodeModeStateFiles,
        private val steps: List<CodeModeStep>,
        private val failAt: Int,
    ) : CodeModeRuntime {
        var starts = 0
        val cell = RecordingCell()

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
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
                    state.block()
                }
                return step
            }

            override fun close() {
                closed = true
            }
        }
    }
}
