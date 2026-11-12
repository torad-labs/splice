package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag

internal class PerfRecorderBehaviorTest {
    @Test
    fun `scope refinement preserves arrival population and closes the old live gauge`() {
        ObservationFixture().use { fixture ->
            val request = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            fixture.advance(1000)
            request.update(fixture.scope.copy(model = "chosen-model", transport = "sse"))
            request.observe(PerfEventKind.REQUEST_ADMITTED, mapOf("queue_wait_ms" to 1000.0))
            fixture.advance(1000)
            assertTrue(request.finish(OutcomeTag.OK.wire, mapOf("input_tokens" to 100.0, "output_tokens" to 2.0)))
            assertFalse(request.finish(OutcomeTag.OK.wire, mapOf("output_tokens" to 100.0)))
            assertEquals(1.0, fixture.total("requests_arrived"))
            assertEquals(null, fixture.points("requests_arrived").single().scope.model)
            assertEquals("chosen-model", fixture.points("requests_finished").single().scope.model)
            assertEquals(0.0, fixture.points("requests_live").sumOf { it.last ?: 0.0 })
            assertEquals(2.0, fixture.total("output_tokens"))
            assertEquals(1.0, fixture.total("requests_finished"))
        }
    }

    @Test
    fun `first content belongs once to completed request minute and wall jumps cannot make elapsed negative`() {
        ObservationFixture().use { fixture ->
            val request = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            fixture.advance(1000)
            request.observe(PerfEventKind.PROTOCOL_PROGRESS, mapOf("upstream_events" to 1.0))
            request.observe(PerfEventKind.CONTENT_PROGRESS, mapOf(FIRST_MEANINGFUL_CONTENT_MS to 1000.0))
            request.observe(PerfEventKind.CONTENT_PROGRESS, mapOf(FIRST_MEANINGFUL_CONTENT_MS to 2000.0))
            assertTrue(fixture.points(FIRST_MEANINGFUL_CONTENT_MS).isEmpty())
            fixture.advance(MINUTE)
            fixture.wall = -MINUTE
            request.finish(OutcomeTag.OK.wire)
            val first = fixture.points(FIRST_MEANINGFUL_CONTENT_MS).single()
            assertEquals(1L, first.count)
            assertEquals(1000.0, first.sum)
            assertEquals(MINUTE, first.fromMs)
            assertTrue(fixture.points("arrival_to_terminal_ms").single().sum >= 0)
            assertTrue(fixture.accumulator.snapshot().coverage.issues.any { it.code == "clock_discontinuity" })
        }
    }

    @Test
    fun `completion cannot invent content timings or replace observed content boundaries`() {
        ObservationFixture().use { fixture ->
            val contentMetrics = listOf(
                FIRST_MEANINGFUL_CONTENT_MS,
                "arrival_to_first_reasoning_ms",
                "arrival_to_first_text_ms",
                "arrival_to_first_tool_ms",
            )
            val suppliedTimings = contentMetrics.associateWith { 0.0 }
            val heartbeatOnly = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            heartbeatOnly.observe(PerfEventKind.PROTOCOL_PROGRESS, mapOf("upstream_events" to 1.0))
            heartbeatOnly.finish(
                OutcomeTag.OK.wire,
                suppliedTimings + mapOf("queue_wait_ms" to 2.0, "output_tokens" to 1.0),
            )
            assertTrue(contentMetrics.all { fixture.points(it).isEmpty() })
            assertEquals(
                PerfObjectiveVerdict.UNKNOWN,
                PerfObjectives.evaluate(OutcomeTag.OK.wire, emptyMap(), PerfObjectiveSet("content", "one", 1000.0)),
            )
            assertEquals(2.0, fixture.total("queue_wait_ms"))
            assertEquals(1.0, fixture.total("output_tokens"))
            val observed = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            fixture.advance(1000)
            observed.observe(PerfEventKind.CONTENT_PROGRESS, contentMetrics.associateWith { 1000.0 })
            observed.finish(OutcomeTag.OK.wire, suppliedTimings)
            contentMetrics.forEach { metric ->
                val point = fixture.points(metric).single()
                assertEquals(1L, point.count)
                assertEquals(1000.0, point.sum)
            }
        }
    }

    @Test
    fun `attempts outlive client request while duplicate attempt terminals remain idempotent`() {
        ObservationFixture().use { fixture ->
            val request = fixture.recorder.request(fixture.scope, 0, 0)
            val attempt = request.links.copy(attemptId = "attempt-one")
            fixture.recorder.emit(fixture.scope, PerfEventKind.ATTEMPT_STARTED, links = attempt)
            request.finish(OutcomeTag.CANCELLED.wire)
            assertEquals(1.0, fixture.points("attempts_live").single().last)
            fixture.advance(1000)
            repeat(2) {
                fixture.recorder.emit(
                    fixture.scope,
                    PerfEventKind.ATTEMPT_FINISHED,
                    mapOf("attempt_duration_ms" to 1000.0),
                    attempt,
                    OutcomeTag.CANCELLED.wire,
                )
            }
            assertEquals(0.0, fixture.points("attempts_live").single().last)
            assertEquals(1.0, fixture.total("attempts_finished"))
            assertEquals(1L, fixture.points("attempt_duration_ms").single().count)
        }
    }

    @Test
    fun `unknown fields and content cannot become exported metrics and drops are observable`() {
        ObservationFixture().use { fixture ->
            assertFalse(
                fixture.recorder.emit(
                    fixture.scope,
                    PerfEventKind.PROTOCOL_PROGRESS,
                    mapOf("prompt_secret" to 1.0, "upstream_bytes" to Double.NaN),
                ),
            )
            assertTrue(fixture.points("prompt_secret").isEmpty())
            assertEquals(2.0, fixture.total("self_dropped"))
            assertFalse(PerfMetricRegistry.permits("prompt_secret", PerfProjection.EXPORT))
            assertFalse(PerfMetricRegistry.permits("total", PerfProjection.EXPORT))
        }
    }
}

private const val MINUTE = 60000L
private const val FIRST_MEANINGFUL_CONTENT_MS = "arrival_to_first_meaningful_content_ms"
