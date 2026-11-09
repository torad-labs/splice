package splice.usage.perf.objectives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.usage.perf.query.PerfQuery
import java.nio.file.Path

class PerfAlertCompletedWindowTest {
    @Test
    fun `continuously lagging writer produces exact goodput at completed windows with real hold timestamps`(
        @TempDir tmp: Path,
    ) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(metric = "goodput.good_fraction", threshold = 0.5, recovery = 0.9))
            fixture.horizonLag = 5_000
            val current =
                takeReply(
                    fixture.query.summary(
                        PerfQuery(60_000, 120_000, mapOf("head" to "h"), fixture.store.objective("latency")),
                    ),
                )
            assertNull(current.obj("goodput").number("good_fraction"))
            val pending = fixture.evaluate(120_000).event()
            assertEquals("pending", pending.text("state"))
            assertEquals(0.0, pending.number("observed"))
            assertEquals(55_000L, pending.obj("window").long("from_ms"))
            assertEquals(115_000L, pending.obj("window").long("until_ms"))
            assertEquals(120_000L, pending.long("evaluated_at_ms"))
            assertEquals(120_000L, pending.long("data_as_of_ms"))
            assertEquals(5_000L, pending.long("window_lag_ms"))
            assertEquals(COMPLETED_REQUEST_WINDOW, pending.text("window_basis"))
            assertEquals("committed_observations_as_of", pending.obj("coverage").text("coverage_basis"))
            assertEquals("false", pending.obj("coverage").text("event_time_final"))
            assertEquals(5_000L, fixture.evaluate(125_000).event().long("hold_observed_ms"))
            val firing = fixture.evaluate(130_000).event()
            assertEquals("firing", firing.text("state"))
            assertEquals(130_000L, firing.long("firing_since_ms"))
            assertEquals(125_000L, firing.obj("window").long("until_ms"))
            assertEquals(130_000L, takeReply(fixture.engine.status()).long("evaluated_at_ms"))
        }
    }

    @Test
    fun `frozen horizon cannot cash wall clock time into fresh hold evidence`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure()
            fixture.horizonOverride = 115_000
            fixture.evaluate(120_000)
            val frozen = fixture.evaluate(130_000).event()
            assertEquals("insufficient_data", frozen.text("state"))
            assertEquals(COMPLETED_UNCHANGED, frozen.text("reason"))
            assertEquals(0L, frozen.long("hold_observed_ms"))
            fixture.horizonOverride = 116_000
            val advanced = fixture.evaluate(135_000).event()
            assertEquals("pending", advanced.text("state"))
            assertEquals(1_000L, advanced.long("hold_observed_ms"))
            fixture.horizonOverride = 125_000
            assertEquals(6_000L, fixture.evaluate(140_000).event().long("hold_observed_ms"))
            fixture.horizonOverride = 130_000
            val firing = fixture.evaluate(145_000).event()
            assertEquals("firing", firing.text("state"))
            assertEquals(145_000L, firing.long("firing_since_ms"))
        }
    }

    @Test
    fun `one second rules tolerate normal five second durability lag without inventing hold continuity`(
        @TempDir tmp: Path,
    ) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(hold = 0).replace("\"window_ms\":60000", "\"window_ms\":1000"))
            fixture.horizonLag = 5_000
            val firing = fixture.evaluate(120_000).event()
            assertEquals("firing", firing.text("state"))
            assertEquals(15_000L, firing.long("max_window_lag_ms"))
            assertEquals(114_000L, firing.obj("window").long("from_ms"))
            fixture.configure(objectiveSettings().replace("\"window_ms\":60000", "\"window_ms\":1000"))
            fixture.evaluate(125_000)
            val gap = fixture.evaluate(130_000).event()
            assertEquals("pending", gap.text("state"))
            assertEquals(130_000L, gap.long("pending_since_ms"))
            assertEquals(0L, gap.long("hold_observed_ms"))
        }
    }
}
