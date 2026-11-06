package splice.usage.perf.objectives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfObjectiveVerdict
import splice.core.perf.observation.PerfObjectives
import splice.usage.perf.query.PerfQuery
import java.nio.file.Path

class PerfAlertEvidenceTest {
    @Test
    fun `goodput rule uses query evaluator population and refuses lower bound when interval coverage is unknown`(
        @TempDir tmp: Path,
    ) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(
                objectiveSettings(
                    metric = "goodput.good_fraction",
                    hold = 0,
                    threshold = 0.9,
                    recovery = 0.95,
                ),
            )
            val objective = requireNotNull(fixture.store.objective("latency"))
            assertEquals(
                PerfObjectiveVerdict.BAD,
                PerfObjectives.evaluate(
                    OutcomeTag.OK.wire,
                    mapOf("arrival_to_terminal_ms" to fixture.duration),
                    objective,
                ),
            )
            val query = PerfQuery(60_000, 120_000, mapOf("head" to "h"), objective)
            val goodput = takeReply(fixture.query.summary(query)).obj("goodput")
            val alert = fixture.evaluate(120_000).event()
            assertEquals(goodput.number("good_fraction"), alert.number("observed"))
            assertEquals(goodput.long("known_evaluable"), alert.long("sample_count"))
            assertEquals("firing", alert.text("state"))
            fixture.covered = false
            fixture.duration = 10.0
            val partial = fixture.evaluate(130_000).event()
            assertEquals("insufficient_data", partial.text("state"))
            assertEquals("exact_goodput_unavailable", partial.text("reason"))
            assertEquals("true", partial.text("active"))
            assertNull(partial.number("observed"))
            val known = takeReply(fixture.query.summary(query.copy(fromMs = 70_000, untilMs = 130_000))).obj("goodput")
            assertEquals(2L, known.long("good"))
            assertEquals(2L, known.long("known_evaluable"))
            assertNull(known.number("per_s"))
        }
    }

    @Test
    fun `raw refused terminals count as refusal without cumulative gate sample substitution`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(metric = "refusal_fraction", hold = 0, threshold = 0.5, recovery = 0.1))
            fixture.outcome = OutcomeTag.AT_CAPACITY.wire
            val alert = fixture.evaluate(120_000).event()
            assertEquals(1.0, alert.number("observed"))
            assertEquals(2L, alert.long("sample_count"))
            assertEquals("firing", alert.text("state"))
        }
    }

    @Test
    fun `unsupported sink delta evidence stays insufficient even with complete request samples`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(
                objectiveSettings(metric = "telemetry.loss_fraction", hold = 0, threshold = 0.1, recovery = 0.0),
            )
            val alert = fixture.evaluate(120_000).event()
            assertEquals("insufficient_data", alert.text("state"))
            assertEquals("sink_totals_lack_window_delta", alert.text("reason"))
            assertNull(alert.number("observed"))
        }
    }
}
