package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag

internal class PerfAttemptBehaviorTest {
    @Test
    fun `protocol milestones record first valid boundary once while progress counts keep accumulating`() {
        ObservationFixture().use { fixture ->
            val request = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            val attempt = request.attempt("http")
            val metrics = listOf(
                "http_request_write_ms",
                "http_response_headers_ms",
                "http_first_body_byte_ms",
                "ws_send_accepted_ms",
                "ws_first_fragment_ms",
            )
            assertFalse(attempt.observe(PerfEventKind.PROTOCOL_PROGRESS, metrics.associateWith { Double.NaN }))
            val progress = mapOf("upstream_events" to 1.0)
            assertTrue(attempt.observe(PerfEventKind.PROTOCOL_PROGRESS, metrics.associateWith { 1000.0 } + progress))
            assertFalse(attempt.observe(PerfEventKind.PROTOCOL_PROGRESS, metrics.associateWith { 2000.0 } + progress))
            attempt.finish(OutcomeTag.OK.wire)
            request.finish(OutcomeTag.OK.wire)
            metrics.forEach { metric ->
                val point = fixture.points(metric).single()
                assertEquals(1L, point.count)
                assertEquals(1000.0, point.sum)
            }
            assertEquals(2.0, fixture.total("upstream_events"))
        }
    }

    @Test
    fun `attempt identity and duration remain independent after client completion`() {
        ObservationFixture().use { fixture ->
            val request = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            request.update(fixture.scope, request.links.copy(traceId = TRACE_ID, spanId = PARENT_SPAN))
            val first = request.attempt("http")
            fixture.advance(1000)
            first.finish(OutcomeTag.UPSTREAM_FAILED.wire)
            val retry = request.attempt("ws")
            assertNotEquals(first.links.attemptId, retry.links.attemptId)
            assertEquals(request.links.requestId, retry.links.requestId)
            assertEquals(TRACE_ID, retry.links.traceId)
            assertEquals(PARENT_SPAN, retry.links.parentSpanId)
            assertNotEquals(first.links.spanId, retry.links.spanId)
            request.finish(OutcomeTag.CANCELLED.wire)
            assertEquals(1.0, fixture.points("attempts_live").sumOf { it.last ?: 0.0 })
            fixture.advance(1000)
            assertTrue(retry.observe(PerfEventKind.PROTOCOL_PROGRESS, mapOf("ws_first_fragment_ms" to 2000.0)))
            assertTrue(retry.finish(OutcomeTag.OK.wire, mapOf("attempt_duration_ms" to 0.0)))
            assertFalse(retry.finish(OutcomeTag.OK.wire))
            assertEquals(0.0, fixture.points("attempts_live").sumOf { it.last ?: 0.0 })
            assertEquals(2.0, fixture.total("attempts_finished"))
            assertEquals(2000.0, fixture.total("attempt_duration_ms"))
            assertTrue(fixture.points("attempt_duration_ms").all { it.scope.work == "upstream" })
            assertEquals(1.0, fixture.total("requests_finished"))
        }
    }
}

private const val TRACE_ID = "11111111111111111111111111111111"
private const val PARENT_SPAN = "2222222222222222"
