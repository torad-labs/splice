package splice.observability.export

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.util.ElapsedClock
import splice.core.util.WallClock

class ExportRequestTraceTest {
    @Test
    fun `terminal can supply attached context when its checkpoint was dropped`() {
        RecordedExport(deliverCheckpoints = false).use { output ->
            val request = output.recorder.request(ExportFixtures.scope, 1_000, 1_000)
            output.now = 1_010
            request.update(request.scope, ExportFixtures.links)
            output.now = 1_250
            assertTrue(request.finish("ok"))
            val root = output.spans.single()
            assertEquals(ExportFixtures.links.traceId, root.traceId)
            assertEquals(ExportFixtures.links.spanId, root.spanId)
            assertEquals(1_000 * NANOS_PER_MS, root.startEpochNanos)
            assertEquals(1_250 * NANOS_PER_MS, root.endEpochNanos)
        }
    }

    @Test
    fun `actual arrival then attach checkpoint preserves arrival time and request attempt tree`() {
        RecordedExport().use { output ->
            val request = output.recorder.request(ExportFixtures.scope, 1_000, 1_000)
            assertNull(output.observations.single().links.traceId)
            output.now = 1_010
            // RequestObservation.attach calls this update after recorder.request has published its arrival.
            val incoming = ExportFixtures.links.copy(parentSpanId = "abcdef1234567890")
            request.update(request.scope, incoming)
            assertEquals(PerfEventKind.CHECKPOINT, output.observations.last().kind)
            output.now = 1_020
            val attempt = request.attempt("sse")
            output.now = 1_200
            assertTrue(attempt.finish("ok"))
            output.now = 1_250
            assertTrue(request.finish("ok"))
            val root = output.spans.single { it.name == "splice.request" }
            val child = output.spans.single { it.name == "splice.attempt" }
            assertEquals(incoming.traceId, root.traceId)
            assertEquals(incoming.spanId, root.spanId)
            assertEquals(incoming.parentSpanId, root.parentSpanId)
            assertEquals(root.traceId, child.traceId)
            assertEquals(root.spanId, child.parentSpanId)
            assertEquals(1_000 * NANOS_PER_MS, root.startEpochNanos)
            assertEquals(1_250 * NANOS_PER_MS, root.endEpochNanos)
            assertFalse(root.attributes.toString().contains(requireNotNull(request.links.requestId)))
            assertEquals(0L, output.traces.dropped)
        }
    }

    @Test
    fun `terminal without attached context is explicit and does not block a later refusal span`() {
        RecordedExport().use { output ->
            val unlinked = output.recorder.request(ExportFixtures.scope, 1_000, 1_000)
            assertTrue(unlinked.finish("refused"))
            assertTrue(output.spans.isEmpty())
            assertEquals(1L, output.traces.missingContext)
            assertEquals(1L, output.traces.dropped)
            val linked = output.recorder.request(ExportFixtures.scope, 1_000, 1_000)
            linked.update(linked.scope, ExportFixtures.links)
            output.now = 1_001
            assertTrue(linked.finish("refused"))
            assertEquals(ExportFixtures.links.spanId, output.spans.single().spanId)
            assertEquals(1_000 * NANOS_PER_MS, output.spans.single().startEpochNanos)
        }
    }

    @Test
    fun `standalone MCP tool has its own trace and no request lifecycle`() {
        RecordedExport().use { output ->
            val tool = output.recorder.tool(ExportFixtures.scope.copy(transport = "mcp"))
            output.now = 1_010
            assertTrue(tool.finish("ok"))
            val span = output.spans.single()
            assertEquals("splice.tool", span.name)
            assertEquals("mcp", span.attributes.get(AttributeKey.stringKey("transport")))
            assertTrue(span.spanContext.isValid)
            assertFalse(span.parentSpanContext.isValid)
            assertTrue(output.observations.all { it.kind.name.startsWith("TOOL_") })
            assertTrue(output.observations.all { it.links.requestId == null && it.links.attemptId == null })
        }
    }
}

private class RecordedExport(private val deliverCheckpoints: Boolean = true) : AutoCloseable {
    var now = 1_000L
    val traces = ExportTraces()
    val observations = ArrayList<PerfObservation>()
    val spans = ArrayList<SpanData>()
    val recorder = PerfRecorder(
        ExportFixtures.identity,
        PerfObserver { event ->
            observations += event
            if (event.kind == PerfEventKind.CHECKPOINT && !deliverCheckpoints) {
                false
            } else {
                traces.accept(ExportEvents.of(event))?.let(spans::add)
                true
            }
        },
        ElapsedClock { now },
        WallClock { now },
    )

    override fun close() = traces.close()
}
