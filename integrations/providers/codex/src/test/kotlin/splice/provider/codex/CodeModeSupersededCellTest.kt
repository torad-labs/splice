package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.codemode.CodeModeStep

class CodeModeSupersededCellTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a later request without the parked callback closes its cell immediately`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))))))
        val manager = bridge(runtime)
        try {
            val first = RecordingSink()
            manager.interceptor(turn(), outer(), false).intercept(BASE_REQUEST, first) { outerOutcome() }
            val original = stateFiles.records().single()
            val next = """{"input":[{"role":"developer","content":"s"},{"role":"user","content":"next task"}]}"""
            manager.interceptor(turn(), null, false).intercept(next, RecordingSink()) { completedOutcome() }
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
            manager.interceptor(turn(), outer(), false).intercept(BASE_REQUEST, first) { outerOutcome() }
            val id = first.tools.single().id
            manager.interceptor(turn(), null, false).intercept(requestWithCall(id), RecordingSink()) {
                completedOutcome()
            }
            assertFalse(runtime.cell.closed)
            assertEquals(1, runtime.cell.advances)
        } finally {
            manager.onHeadStop()
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
                outerOutcome("outer-a")
            }
            manager.interceptor(turn(model = "gpt-6-sol"), outer("outer-b"), false)
                .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("outer-b") }
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
