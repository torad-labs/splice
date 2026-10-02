// NEW: the provider addresses and expires runtime engines with its existing conversation key.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeCapacityException
import kotlin.time.Duration.Companion.hours

class CodeModeSessionOwnershipTest : CodeModeBridgeTestSupport() {
    @Test
    fun `provider starts use the persisted session conversation and model identity`() = runTest {
        val runtime = AddressedRuntime()
        val manager = bridge(runtime)
        manager.interceptor(turn(sessionId = "fixture-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-a") }
        manager.interceptor(turn(sessionId = "fixture-b"), outer("outer-b"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-b") }
        val keys = stateFiles.records().map { it.getValue("key").jsonPrimitive.content }.toSet()
        assertEquals(keys, runtime.starts.toSet())
        assertEquals(2, keys.size)
        assertNotEquals(runtime.starts[0], runtime.starts[1])
        manager.onHeadStop()
    }

    @Test
    fun `expired code mode state closes exactly its session engine`() = runTest {
        val clock = MutableClock(1_000)
        val runtime = AddressedRuntime()
        val manager = bridge(runtime, ttl = 1.hours, clock = clock)
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        val key = runtime.starts.single()
        clock.now += 2.hours.inWholeMilliseconds
        sweepOwnHistory(manager)
        assertEquals(listOf(key), runtime.ended)
        assertTrue(runtime.cells.single().closed)
        manager.onHeadStop()
    }

    @Test
    fun `positive session death closes its engine without closing a live sibling`() = runTest {
        val runtime = AddressedRuntime()
        val manager = bridge(runtime)
        manager.interceptor(turn(sessionId = "session-a"), outer("outer-a"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-a") }
        manager.interceptor(turn(sessionId = "session-b"), outer("outer-b"), disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-b") }
        deadSessions += "session-a"
        manager.interceptor(turn(sessionId = "session-a"), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        assertEquals(listOf(runtime.starts.first()), runtime.ended)
        assertTrue(runtime.cells.first().closed)
        assertEquals(false, runtime.cells.last().closed)
        manager.onHeadStop()
    }

    @Test
    fun `capacity reaches the model with the configured cap and its operator knob`() = runTest {
        val runtime = object : CodeModeRuntime {
            override suspend fun start(
                source: String,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell = throw CodeModeCapacityException("24576 MiB head budget: quirks.code_mode_memory_mb")

            override fun close() = Unit
        }
        val manager = bridge(runtime)
        var posted = ""
        var posts = 0
        val outcome = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(BASE_REQUEST, RecordingSink()) { body ->
                posted = body
                if (posts++ == 0) outerOutcome() else completedOutcome()
            }
        assertTrue(outcome is splice.core.turn.TurnOutcome.Success)
        assertTrue("24576 MiB" in posted)
        assertTrue("quirks.code_mode_memory_mb" in posted)
        manager.onHeadStop()
    }

    private class AddressedRuntime : CodeModeRuntime {
        val starts = mutableListOf<String>()
        val ended = mutableListOf<String>()
        val cells = mutableListOf<ScriptedCell>()

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = error("Provider starts must carry their existing session identity")

        override suspend fun startSession(
            sessionKey: String,
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            starts += sessionKey
            val calls = CodeModeStep.Calls(listOf(CodeModeCall("1", "Read", JsonObject(emptyMap()))))
            return ScriptedCell(ArrayDeque(listOf(calls))).also(cells::add)
        }

        override fun closeSession(sessionKey: String) {
            ended += sessionKey
        }

        override fun close() = Unit
    }
}
