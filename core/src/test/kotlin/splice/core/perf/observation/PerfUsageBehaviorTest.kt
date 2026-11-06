package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.aggregate.PerfObservationLimits

internal class PerfUsageBehaviorTest {
    @Test
    fun `invalid early bill leaves canonical slot available for valid finish or late usage`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            ObservationFixture().use { fixture ->
                val atFinish = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
                assertFalse(atFinish.usage(mapOf(OUTPUT_TOKENS to invalid)))
                assertTrue(atFinish.finish(OutcomeTag.OK.wire, mapOf(OUTPUT_TOKENS to 2.0)))
                assertEquals(2.0, fixture.total(OUTPUT_TOKENS))
                val late = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
                assertFalse(late.usage(mapOf(OUTPUT_TOKENS to invalid)))
                late.finish(OutcomeTag.OK.wire)
                fixture.advance(MINUTE)
                assertTrue(late.usage(mapOf(OUTPUT_TOKENS to 2.0)))
                assertEquals(2L, fixture.points(OUTPUT_TOKENS).single().count)
                assertEquals(2.0 + 2.0, fixture.total(OUTPUT_TOKENS))
                assertEquals(2.0, fixture.total("requests_finished"))
            }
        }
    }

    @Test
    fun `early canonical usage waits for terminal and takes precedence over legacy finish values`() {
        ObservationFixture().use { fixture ->
            val request = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            assertTrue(request.usage(mapOf("input_tokens" to 100.0, OUTPUT_TOKENS to 2.0)))
            assertTrue(fixture.points(OUTPUT_TOKENS).isEmpty())
            fixture.advance(MINUTE)
            request.finish(OutcomeTag.OK.wire, mapOf(OUTPUT_TOKENS to 100.0))
            assertFalse(request.usage(mapOf(OUTPUT_TOKENS to 1000.0)))
            val output = fixture.points(OUTPUT_TOKENS).single()
            assertEquals(2.0, output.sum)
            assertEquals(MINUTE, output.fromMs)
            assertEquals(1.0, fixture.total("requests_finished"))
        }
    }

    @Test
    fun `late canonical usage updates original terminal minute without another completion or clock rollback`() {
        ObservationFixture().use { fixture ->
            val request = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            fixture.advance(1000)
            request.finish(OutcomeTag.OK.wire)
            val original = fixture.accumulator.snapshot()
            fixture.advance(MINUTE)
            assertTrue(request.usage(mapOf(OUTPUT_TOKENS to 2.0)))
            assertFalse(request.usage(mapOf(OUTPUT_TOKENS to 100.0)))
            val snapshot = fixture.accumulator.snapshot()
            val output = snapshot.points.single { it.metric == OUTPUT_TOKENS }
            assertEquals(0L, output.fromMs)
            assertEquals("good", output.scope.outcome)
            assertEquals(2.0, output.sum)
            assertEquals(1.0, fixture.total("requests_finished"))
            assertTrue(snapshot.coverage.issues.any { it.code == "late_observation" })
            assertTrue(snapshot.coverage.issues.none { it.code == "clock_discontinuity" })
            assertTrue(original.points.none { it.metric == OUTPUT_TOKENS })
        }
    }

    @Test
    fun `legacy finish usage is also canonical and expired late usage cannot resurrect old minutes`() {
        ObservationFixture().use { fixture ->
            val legacy = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            legacy.finish(OutcomeTag.OK.wire, mapOf(OUTPUT_TOKENS to 2.0))
            assertFalse(legacy.usage(mapOf(OUTPUT_TOKENS to 100.0)))
            assertEquals(2.0, fixture.total(OUTPUT_TOKENS))
            val late = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            late.finish(OutcomeTag.OK.wire)
            fixture.advance((PerfObservationLimits.RETAINED_MINUTES + 1) * MINUTE)
            assertFalse(late.usage(mapOf(OUTPUT_TOKENS to 1000.0)))
            val snapshot = fixture.accumulator.snapshot()
            assertTrue(snapshot.points.none { it.metric == OUTPUT_TOKENS })
            assertTrue(snapshot.coverage.issues.any { it.code == "late_observation_expired" })
        }
    }
}

private const val MINUTE = 60000L
private const val OUTPUT_TOKENS = "output_tokens"
