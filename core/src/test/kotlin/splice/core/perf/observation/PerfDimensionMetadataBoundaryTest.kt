package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.ElapsedClock
import splice.core.util.WallClock

internal class PerfDimensionMetadataBoundaryTest {
    @Test
    fun `recorder preserves exact accepted boundaries and redacts invalid link metadata`() {
        val emitted = mutableListOf<PerfObservation>()
        val recorder = PerfRecorder(
            PerfIdentity("boot", "build", "version", "revision"),
            PerfObserver { emitted += it; true },
            ElapsedClock { 0L },
            WallClock { 0L },
        )

        assertTrue(
            recorder.emit(
                PerfScope(head = "H".repeat(127) + "/", model = "M".repeat(129)),
                PerfEventKind.PROTOCOL_PROGRESS,
                links = PerfLinks(
                    requestId = "R".repeat(160),
                    attemptId = "A".repeat(161),
                    toolId = "tool\nsecret",
                    sessionId = "S".repeat(160),
                    traceId = "f".repeat(32),
                    spanId = "0".repeat(16),
                    parentSpanId = "a".repeat(16),
                    traceFlags = "00",
                    traceState = "vendor=account-secret",
                    turnId = "t",
                ),
            ),
        )

        val observation = emitted.single()
        assertEquals("H".repeat(127) + "/", observation.scope.head)
        assertNull(observation.scope.model)
        assertEquals("R".repeat(160), observation.links.requestId)
        assertNull(observation.links.attemptId)
        assertNull(observation.links.toolId)
        assertEquals("S".repeat(160), observation.links.sessionId)
        assertEquals("f".repeat(32), observation.links.traceId)
        assertNull(observation.links.spanId)
        assertEquals("a".repeat(16), observation.links.parentSpanId)
        assertEquals("00", observation.links.traceFlags)
        assertNull(observation.links.traceState)
        assertEquals("t", observation.links.turnId)
    }

    @Test
    fun `recorder rejects non-ASCII newline uppercase and over-limit metadata at output`() {
        val emitted = mutableListOf<PerfObservation>()
        val recorder = PerfRecorder(
            PerfIdentity("boot", "build", "version", "revision"),
            PerfObserver { emitted += it; true },
            ElapsedClock { 0L },
            WallClock { 0L },
        )

        recorder.emit(
            PerfScope(head = "_starts-with-punctuation", model = "m\nmodel"),
            PerfEventKind.PROTOCOL_PROGRESS,
            links = PerfLinks(
                requestId = "é-id",
                attemptId = "line\nbreak",
                projectId = "project/private",
                traceId = "F".repeat(32),
                spanId = "b".repeat(16),
                traceFlags = "0A",
            ),
        )

        val observation = emitted.single()
        assertEquals("unknown", observation.scope.head)
        assertNull(observation.scope.model)
        assertNull(observation.links.requestId)
        assertNull(observation.links.attemptId)
        assertNull(observation.links.projectId)
        assertNull(observation.links.traceId)
        assertEquals("b".repeat(16), observation.links.spanId)
        assertNull(observation.links.traceFlags)
    }
}
