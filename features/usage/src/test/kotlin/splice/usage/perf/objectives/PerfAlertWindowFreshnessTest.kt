package splice.usage.perf.objectives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PerfAlertWindowFreshnessTest {
    @Test
    fun `missing future stale and regressed horizons remain explicit insufficient data`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure()
            fixture.horizonAvailable = false
            assertEquals("completed_window_unavailable", fixture.evaluate(120_000).event().text("reason"))
            fixture.horizonAvailable = true
            fixture.horizonOverride = 140_000
            assertEquals("completed_window_in_future", fixture.evaluate(125_000).event().text("reason"))
            fixture.horizonOverride = 120_000
            assertEquals("pending", fixture.evaluate(130_000).event().text("state"))
            fixture.horizonOverride = 119_000
            assertEquals("completed_window_regressed", fixture.evaluate(135_000).event().text("reason"))
            fixture.horizonOverride = 120_000
            val stale = fixture.evaluate(185_000).event()
            assertEquals("completed_window_stale", stale.text("reason"))
            assertEquals(65_000L, stale.long("window_lag_ms"))
            assertEquals(60_000L, stale.long("max_window_lag_ms"))
            assertEquals(0L, stale.long("hold_observed_ms"))
        }
    }

    @Test
    fun `repeated windows cannot recover an active incident and restart drops unproven continuity`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(hold = 0))
            fixture.horizonLag = 5_000
            val firing = fixture.evaluate(120_000).event()
            fixture.duration = 10.0
            fixture.horizonOverride = 120_000
            assertEquals("recovery_pending", fixture.evaluate(125_000).event().text("reason"))
            val frozen = fixture.evaluate(135_000).event()
            assertEquals("insufficient_data", frozen.text("state"))
            assertEquals("true", frozen.text("active"))
            assertEquals(0L, frozen.long("recovery_observed_ms"))
            fixture.restart()
            assertEquals(COMPLETED_UNCHANGED, fixture.evaluate(140_000).event().text("reason"))
            fixture.horizonOverride = 140_000
            val restarted = fixture.evaluate(145_000).event()
            assertEquals("recovery_pending", restarted.text("reason"))
            assertEquals(0L, restarted.long("recovery_observed_ms"))
            assertEquals(firing.text("alert_id"), restarted.text("alert_id"))
            fixture.horizonOverride = 150_000
            val recovered = fixture.evaluate(155_000).event()
            assertEquals("resolved", recovered.text("state"))
            assertEquals(155_000L, recovered.long("resolved_at_ms"))
        }
    }
}
