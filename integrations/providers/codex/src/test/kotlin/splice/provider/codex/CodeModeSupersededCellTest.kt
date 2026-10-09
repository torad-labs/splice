package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.provider.codex.state.CodeModeSessionEnd
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep
import kotlin.time.Duration.Companion.hours

class CodeModeSupersededCellTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a later request without the parked callback closes its cell immediately`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))))))
        val manager = bridge(runtime)
        try {
            val first = RecordingSink()
            manager.interceptor(turn(), outer(), false).intercept(BASE_REQUEST, first) {
                RoundResult.Outcome(outerOutcome())
            }
            val original = stateFiles.records().single()
            val next = """{"input":[{"role":"developer","content":"s"},{"role":"user","content":"next task"}]}"""
            manager.interceptor(turn(), null, false).intercept(next, RecordingSink()) {
                RoundResult.Outcome(completedOutcome())
            }
            assertTrue(runtime.cell.closed, "the next history cannot deliver this outer call's result")
            assertEquals(1, runtime.cell.advances, "supersession never reruns or advances source")
            val lost = stateFiles.records().single()
            assertEquals("LOST", lost.getValue("phase").jsonPrimitive.content)
            assertTrue("source was not rerun" in lost.getValue("error").jsonPrimitive.content)
            assertEquals(original["updatedAt"], lost["updatedAt"], "reclaiming is not a client use")
            assertEquals(original["pending"], lost["pending"])
            assertEquals(original["issued"], lost["issued"])
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    fun `an unanswered callback still present in history keeps its cell`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))))))
        val manager = bridge(runtime)
        try {
            val first = RecordingSink()
            manager.interceptor(turn(), outer(), false).intercept(BASE_REQUEST, first) {
                RoundResult.Outcome(outerOutcome())
            }
            val id = first.tools.single().id
            manager.interceptor(turn(), null, false).intercept(requestWithCall(id), RecordingSink()) {
                RoundResult.Outcome(completedOutcome())
            }
            assertFalse(runtime.cell.closed)
            assertEquals(1, runtime.cell.advances)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    fun `pre-conversation-field state loads as an independent key and never closes its parked instance`() {
        val location = stateLocation()
        val saved = CodeModeRecords.of("synthetic-legacy", 0, 1_000).also {
            it.phase = CodeModePhase.STARTING
            it.sessionId = "synthetic-shared-session"
        }.snapshot()
        val json = Json { encodeDefaults = true }
        CodexCodeModeStore(location, json, {}).save(listOf(saved.restore()), emptyList())
        stateFiles.rewriteRecords { JsonObject(it - "conversationId") }
        val closed = mutableListOf<String>()
        val config = CodeModeBridgeConfig({ error("not needed") }, location, clock = MutableClock(1_000))
        val registry = CodexCodeModeRegistry(config, json, 1.hours, closeSession = CodeModeSessionEnd(closed::add))
        val loaded = registry.recordsFor(saved.key).single()
        assertNull(loaded.conversationId)
        val cell = ScriptedCell(ArrayDeque())
        assertTrue(registry.add(loaded))
        assertTrue(registry.attach(loaded, cell))
        registry.retainedCells.acquire(loaded)
        registry.retainedCells.release(loaded)
        val incoming = CodeModeRecords.of("synthetic-current", 0, 1_000).also {
            it.sessionId = "synthetic-shared-session"
            it.conversationId = "synthetic-current-conversation"
        }
        try {
            assertTrue(registry.add(incoming))
            assertFalse(cell.closed, "absence of a conversation field is not evidence of supersession")
            assertEquals(CodeModePhase.ACTIVE, loaded.phase)
            assertTrue(closed.isEmpty(), "legacy independent engine must not be retired or closed")
        } finally {
            registry.onHeadStop()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a completed sibling cannot retire an engine key still starting or borrowed`(borrowed: Boolean) {
        val config = CodeModeBridgeConfig({ error("not needed") }, stateLocation(), clock = MutableClock(1_000))
        val closed = mutableListOf<String>()
        val registry = CodexCodeModeRegistry(
            config,
            Json { encodeDefaults = true },
            1.hours,
            closeSession = CodeModeSessionEnd(closed::add),
        )
        val completed = CodeModeRecords.of("synthetic-first-model", 0, 1_000).also {
            it.conversationId = "synthetic-conversation"
        }
        assertTrue(registry.add(completed))
        registry.complete(completed, "done")
        val busy = CodeModeRecords.of(completed.key, 1, 1_000).also {
            it.phase = CodeModePhase.STARTING
            it.conversationId = completed.conversationId
        }
        assertTrue(registry.add(busy))
        val cell = ScriptedCell(ArrayDeque())
        if (borrowed) {
            assertTrue(registry.attach(busy, cell))
            registry.retainedCells.acquire(busy)
        }
        val incoming = CodeModeRecords.of("synthetic-other-model", 0, 1_000).also {
            it.conversationId = completed.conversationId
        }
        try {
            assertTrue(registry.add(incoming))
            assertTrue(closed.isEmpty(), "completed records never justify closing another record's busy engine")
            assertFalse(cell.closed)
        } finally {
            if (borrowed) registry.retainedCells.release(busy)
            registry.onHeadStop()
        }
    }

    @Test
    fun `a model switch reclaims the previous session cell before opening another`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("a", "Read"))))),
                    ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("b", "Read"))))),
                ),
            ),
        )
        val manager = bridge(runtime)
        try {
            manager.interceptor(turn(), outer("outer-a"), false).intercept(BASE_REQUEST, RecordingSink()) {
                RoundResult.Outcome(outerOutcome("outer-a"))
            }
            manager.interceptor(turn(model = "gpt-6-sol"), outer("outer-b"), false)
                .intercept(BASE_REQUEST, RecordingSink()) { RoundResult.Outcome(outerOutcome("outer-b")) }
            assertEquals(2, runtime.starts)
            assertTrue(runtime.cells.first().closed, "one session cannot accumulate model-keyed engines")
            assertFalse(runtime.cells.last().closed)
            assertEquals(1, runtime.cells.count { !it.closed })
            val older = stateFiles.records().single { it["outerCallId"]?.jsonPrimitive?.content == "outer-a" }
            assertEquals("LOST", older.getValue("phase").jsonPrimitive.content)
            assertTrue("source was not rerun" in older.getValue("error").jsonPrimitive.content)
        } finally {
            manager.onHeadStop()
        }
    }
}
