package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag

internal class PerfAccumulatorBehaviorTest {
    @Test
    fun `invalid saturation value cannot fabricate occupied time and reports dropped observation`() {
        ObservationFixture().use { fixture ->
            assertFalse(fixture.recorder.emit(fixture.scope, PerfEventKind.GATE_STATE, mapOf(GATE_SATURATED to 2.0)))
            assertTrue(fixture.points(GATE_SATURATED).isEmpty())
            fixture.advance(1000)
            fixture.recorder.emit(fixture.scope, PerfEventKind.GATE_STATE, mapOf(GATE_SATURATED to 0.0))
            fixture.advance(1000)
            fixture.recorder.emit(fixture.scope, PerfEventKind.CHECKPOINT)
            assertEquals(0.0, fixture.points(GATE_SATURATED).single().weightedSum)
            assertEquals(1.0, fixture.total("self_dropped"))
            assertTrue(fixture.accumulator.snapshot().coverage.issues.any { it.code == "observation_dropped" })
        }
    }

    @Test
    fun `gate means integrate exact limit transitions while cumulative refusals stay gauges`() {
        ObservationFixture().use { fixture ->
            fixture.recorder.emit(
                fixture.scope,
                PerfEventKind.GATE_STATE,
                mapOf("gate_limit" to 2.0, "gate_refused" to 1.0, GATE_SATURATED to 1.0),
            )
            fixture.advance(1000)
            fixture.recorder.emit(
                fixture.scope,
                PerfEventKind.GATE_STATE,
                mapOf("gate_limit" to 1.0, "gate_refused" to 1.0, GATE_SATURATED to 0.0),
            )
            fixture.advance(1000)
            fixture.recorder.emit(fixture.scope, PerfEventKind.CHECKPOINT)
            val first = fixture.accumulator.snapshot()
            assertEquals(first, fixture.accumulator.snapshot())
            val limit = first.points.single { it.metric == "gate_limit" }
            assertEquals(2000L, limit.coveredMs)
            assertEquals(MEAN_LIMIT, limit.weightedSum / limit.coveredMs)
            val refused = first.points.single { it.metric == "gate_refused" }
            assertEquals(1.0, refused.last)
            assertEquals(1.0, refused.weightedSum / refused.coveredMs)
            assertEquals(2000L, first.coverage.observedMs)
            assertEquals(1000.0, first.points.single { it.metric == GATE_SATURATED }.weightedSum)
        }
    }

    @Test
    fun `complete scope cross product stays bounded and overflows explicitly without retaining IDs`() {
        ObservationFixture().use { fixture ->
            repeat(MODEL_STRESS) { index ->
                val scope = fixture.scope.copy(model = "model-$index")
                val request = fixture.recorder.request(scope, fixture.now, fixture.wall)
                request.update(scope, request.links.copy(sessionId = "private-session-$index"))
                request.finish(OutcomeTag.OK.wire)
            }
            val snapshot = fixture.accumulator.snapshot()
            assertTrue(snapshot.points.map { it.scope }.distinct().size <= PerfObservationLimits.MAX_METRIC_SCOPES)
            assertTrue(snapshot.points.any { it.scope.head == "overflow" })
            assertTrue(snapshot.coverage.issues.any { it.code == "cardinality_overflow" })
            assertEquals(MODEL_STRESS.toDouble(), fixture.total("requests_arrived"))
            assertEquals(MODEL_STRESS.toDouble(), fixture.total("requests_finished"))
            assertTrue(snapshot.points.none { it.scope.toString().contains("private-session") })
        }
    }

    @Test
    fun `event gaps and request maxima are separate and histogram merging never averages percentiles`() {
        ObservationFixture().use { fixture ->
            fixture.recorder.emit(fixture.scope, PerfEventKind.GAP, mapOf("upstream_gap_ms" to 1.0))
            fixture.recorder.emit(fixture.scope, PerfEventKind.GAP, mapOf("upstream_gap_ms" to 1000.0))
            val request = fixture.recorder.request(fixture.scope, 0, 0)
            request.finish(OutcomeTag.OK.wire, mapOf("upstream_gap_max_ms" to 1000.0))
            assertEquals(2L, fixture.points("upstream_gap_ms").single().count)
            assertEquals(1L, fixture.points("upstream_gap_max_ms").single().count)
            assertEquals(1.0, PerfHistogram.percentile(fixture.points("upstream_gap_ms"), MEDIAN))
            assertEquals(1000.0, PerfHistogram.percentile(fixture.points("upstream_gap_ms"), TAIL))
            fixture.advance(1000)
            fixture.recorder.emit(fixture.scope, PerfEventKind.CHECKPOINT)
            assertNotEquals(fixture.accumulator.snapshot().fromMs, fixture.accumulator.snapshot().untilMs)
        }
    }

    @Test
    fun `minute state expires explicitly and bounded snapshots do not resurrect expired gauge intervals`() {
        ObservationFixture().use { fixture ->
            fixture.recorder.emit(fixture.scope, PerfEventKind.GATE_STATE, mapOf("gate_limit" to 2.0))
            fixture.advance((PerfObservationLimits.RETAINED_MINUTES + 1) * PerfObservationLimits.AGGREGATE_MINUTE_MS)
            fixture.recorder.emit(fixture.scope, PerfEventKind.CHECKPOINT)
            val snapshot = fixture.accumulator.snapshot()
            assertTrue(snapshot.points.all { it.fromMs >= PerfObservationLimits.AGGREGATE_MINUTE_MS })
            assertTrue(snapshot.coverage.issues.any { it.code == "retention_expired" })
            assertEquals(snapshot, fixture.accumulator.snapshot())
        }
    }
}

private const val MEAN_LIMIT = 1.5
private const val MODEL_STRESS = 1000
private const val MEDIAN = 0.5
private const val TAIL = 0.95
private const val GATE_SATURATED = "gate_saturated"
