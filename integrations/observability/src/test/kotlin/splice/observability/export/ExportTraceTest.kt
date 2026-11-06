package splice.observability.export

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.trace.data.StatusData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfEventKind
import splice.observability.OBSERVABILITY_NANOS_PER_MILLISECOND

class ExportTraceTest {
    @Test
    fun `request and attempt share exact IDs parentage and terminal timestamp`() {
        ExportTraces().use { traces ->
            val parent = ExportFixtures.links
            val child = parent.copy(spanId = "fedcba0987654321", parentSpanId = parent.spanId)
            assertNull(traces.accept(ExportEvents.of(ExportFixtures.event(PerfEventKind.REQUEST_ARRIVED))))
            assertNull(
                traces.accept(ExportEvents.of(ExportFixtures.event(PerfEventKind.ATTEMPT_STARTED, 1_010, child))),
            )
            val attempt =
                requireNotNull(
                    traces.accept(ExportEvents.of(ExportFixtures.event(PerfEventKind.ATTEMPT_FINISHED, 1_200, child))),
                )
            val request =
                requireNotNull(
                    traces.accept(ExportEvents.of(ExportFixtures.event(PerfEventKind.REQUEST_FINISHED, 1_250))),
                )
            assertEquals(parent.traceId, request.traceId)
            assertEquals(parent.spanId, request.spanId)
            assertEquals(request.traceId, attempt.traceId)
            assertEquals(request.spanId, attempt.parentSpanId)
            assertEquals(1_250 * OBSERVABILITY_NANOS_PER_MILLISECOND, request.endEpochNanos)
            assertFalse(request.attributes.toString().contains("secret"))
            assertTrue(request.events.isEmpty())
        }
    }

    @Test
    fun `native span marker suppresses duplicate while sanitizer strips content`() {
        ExportTraces().use { traces ->
            val start = ExportFixtures.event(PerfEventKind.TOOL_STARTED).copy(values = mapOf("tool_native_span" to 1.0))
            assertNull(traces.accept(ExportEvents.of(start)))
            assertNull(traces.accept(ExportEvents.of(ExportFixtures.event(PerfEventKind.TOOL_FINISHED, 1_500))))
            val event = ExportEvents.of(start)
            val native = ExportSpan(
                "secret tool arguments",
                event.context,
                event.parent,
                1,
                2,
                Attributes.builder().put("execution.id", "secret-id").put("prompt", "secret").build(),
                StatusData.create(io.opentelemetry.api.trace.StatusCode.ERROR, "secret exception"),
            )
            val projected = traces.sanitize(native)
            assertEquals(event.context, projected.spanContext)
            assertEquals("splice.tool", projected.name)
            assertEquals(0, projected.attributes.size())
            assertEquals("", projected.status.description)
        }
    }
}
