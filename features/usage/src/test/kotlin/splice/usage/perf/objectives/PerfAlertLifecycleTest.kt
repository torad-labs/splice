package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PerfAlertLifecycleTest {
    @Test
    fun `sustained windows fire once retain incident across missing data and require recovery and cooldown`(
        @TempDir tmp: Path,
    ) {
        ObjectiveFixture(tmp).use { fixture ->
            assertEquals("ok", fixture.configure().text("status"))
            assertEquals("pending", fixture.evaluate(120_000).event().text("state"))
            assertEquals("pending", fixture.evaluate(125_000).event().text("state"))
            val fired = fixture.evaluate(130_000).event()
            assertEquals("firing", fired.text("state"))
            assertEquals(2L, fired.long("sample_count"))
            assertEquals(1.0, fired.obj("coverage").number("fraction"))
            assertTrue(fired["identity"].toString().contains("build"))
            assertTrue(fired["diagnostic_links"].toString().contains("/api/perf/diagnose"))
            assertEquals(fired.text("dedup_key"), fixture.evaluate(135_000).event().text("dedup_key"))
            fixture.count = 0
            val missing = fixture.evaluate(140_000).event()
            assertEquals("insufficient_data", missing.text("state"))
            assertEquals("true", missing.text("active"))
            fixture.count = 2
            fixture.duration = 40.0
            assertEquals("firing", fixture.evaluate(145_000).event().text("state"))
            assertEquals("resolved", fixture.evaluate(155_000).event().text("state"))
            fixture.duration = 200.0
            assertEquals("pending", fixture.evaluate(160_000).event().text("state"))
            assertEquals("cooldown", fixture.evaluate(170_000).event().text("reason"))
            assertEquals("firing", fixture.evaluate(185_000).event().text("state"))
            assertTrue(fixture.delivery.events.isEmpty(), "install and default rules do not enable delivery")
        }
    }

    @Test
    fun `restart resets unproven pending hold retains firing dedup and ignores late windows`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(delivery = true))
            fixture.evaluate(120_000)
            fixture.restart()
            val resumed = fixture.evaluate(125_000).event()
            assertEquals(125_000L, resumed.long("pending_since_ms"))
            assertEquals("restart_gap_hold_reset", resumed.text("continuity"))
            val fired = fixture.evaluate(135_000).event()
            assertEquals("accepted", fired.text("delivery_status"))
            fixture.restart()
            assertEquals("late_window_ignored", fixture.evaluate(130_000).text("reason"))
            val retained = fixture.evaluate(140_000).event()
            assertEquals(fired.text("alert_id"), retained.text("alert_id"))
            assertEquals("restart_delivery_unknown", retained.text("delivery_status"))
            assertEquals(1, fixture.delivery.events.size)
            val history = takeReply(fixture.engine.history(0, 200_000))["events"] as JsonArray
            assertEquals(1, history.count { it.toString().contains("\"state\":\"firing\"") })
        }
    }

    @Test
    fun `no samples cannot recover even at zero minimum temporal coverage`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(hold = 0).replace("\"min_coverage\":0.9", "\"min_coverage\":0"))
            assertEquals("firing", fixture.evaluate(120_000).event().text("state"))
            fixture.count = 0
            val missing = fixture.evaluate(130_000).event()
            assertEquals("insufficient_data", missing.text("state"))
            assertEquals("true", missing.text("active"))
        }
    }
}
