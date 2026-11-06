package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.ElapsedClock
import splice.core.util.WallClock

class PerfToolBehaviorTest {
    @Test
    fun `overlapping tools remain correlated and finish after their request once`() {
        val events = mutableListOf<PerfObservation>()
        var now = 0L
        val recorder =
            PerfRecorder(
                PerfIdentity("boot", "build", "version", "revision"),
                PerfObserver {
                    events.add(it)
                },
                ElapsedClock { now },
                WallClock { now },
            )
        val request = recorder.request(PerfScope(head = "codex"), now, now)
        val first = request.tool()
        val second = request.tool()
        assertNotEquals(first.links.toolId, second.links.toolId)
        assertEquals(request.links.requestId, first.links.requestId)
        assertEquals(null, first.links.attemptId)
        request.finish("good")
        now = 50L
        assertTrue(second.finish("cancelled"))
        now = 90L
        assertTrue(first.finish("good"))
        assertFalse(first.finish("bad"))
        val finished = events.filter { it.kind == PerfEventKind.TOOL_FINISHED }
        assertEquals(listOf(50.0, 90.0), finished.map { it.values["tool_duration_ms"] })
        assertEquals(2, finished.size)
    }

    @Test
    fun `native span parentage is preserved without replacing request correlation`() {
        val events = mutableListOf<PerfObservation>()
        val recorder =
            PerfRecorder(
                PerfIdentity("boot", "build", "version", "revision"),
                PerfObserver {
                    events.add(it)
                },
                ElapsedClock { 0 },
                WallClock { 0 },
            )
        val request = recorder.request(PerfScope(), 0, 0)
        val native =
            PerfLinks(
                requestId = "untrusted",
                traceId = "a".repeat(32),
                spanId = "b".repeat(16),
                parentSpanId = "c".repeat(16),
            )
        val tool = request.tool(native)
        assertEquals(request.links.requestId, tool.links.requestId)
        assertEquals(native.spanId, tool.links.spanId)
        assertEquals(native.parentSpanId, tool.links.parentSpanId)
        assertEquals(1.0, events.last().values["tool_native_span"])
        assertFalse(tool.observe(PerfEventKind.TOOL_PHASE, mapOf("tool_occupied_cells" to 0.5)))
    }
}
