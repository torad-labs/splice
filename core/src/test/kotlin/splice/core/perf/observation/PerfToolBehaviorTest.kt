package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `prepared tool completion keeps measured time and publishes once after finalization`() {
        val events = mutableListOf<PerfObservation>()
        var now = 10L
        val recorder = PerfRecorder(
            PerfIdentity("boot", "build", "version", "revision"),
            PerfObserver { events.add(it) },
            ElapsedClock { now },
            WallClock { now },
        )
        val tool = recorder.tool(PerfScope())
        now = 30L
        val completion = requireNotNull(tool.prepareFinish("cancelled"))
        assertTrue(events.none { it.kind == PerfEventKind.TOOL_FINISHED })
        assertFalse(tool.finish("ok"))
        assertNull(tool.prepareFinish("ok"))
        assertFalse(tool.observe(PerfEventKind.TOOL_PHASE))
        now = 500L // external finalization must not inflate the tool's terminal timing
        assertTrue(completion.publish())
        assertFalse(completion.publish())
        val terminal = events.single { it.kind == PerfEventKind.TOOL_FINISHED }
        assertEquals(30L, terminal.stamp.monotonicMs)
        assertEquals(30L, terminal.stamp.wallMs)
        assertEquals(20.0, terminal.values["tool_duration_ms"])
        assertEquals("cancelled", terminal.outcome)
    }

    @Test
    fun `prepared completion preserves observer refusal without republishing`() {
        val events = mutableListOf<PerfObservation>()
        val recorder = PerfRecorder(
            PerfIdentity("boot", "build", "version", "revision"),
            PerfObserver {
                events.add(it)
                it.kind != PerfEventKind.TOOL_FINISHED
            },
            ElapsedClock { 0 },
            WallClock { 0 },
        )
        val completion = requireNotNull(recorder.tool(PerfScope()).prepareFinish("ok"))
        assertFalse(completion.publish())
        assertFalse(completion.publish())
        assertEquals(1, events.count { it.kind == PerfEventKind.TOOL_FINISHED })
    }
}
