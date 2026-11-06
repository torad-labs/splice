package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PerfAlertConfigurationTest {
    @Test
    fun `configuration changing during query skips publication and delivery of the old generation`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(delivery = true, hold = 0))
            fixture.horizonLag = 5_000
            fixture.settingsDuringRead = objectiveSettings(delivery = false, hold = 0)
            val skipped = fixture.evaluate(120_000)
            assertEquals("settings_changed_during_evaluation", skipped.text("reason"))
            assertTrue((skipped["events"] as JsonArray).isEmpty())
            assertTrue(fixture.delivery.events.isEmpty())
            val current = fixture.evaluate(121_000).event()
            assertEquals(takeReply(fixture.store.settings()).text("revision"), current.text("settings_revision"))
            assertFalse(current.text("delivery_enabled") == "true")
            assertEquals(121_000L, current.long("evaluated_at_ms"))
            assertEquals(116_000L, current.obj("window").long("until_ms"))
        }
    }

    @Test
    fun `removing an active rule retires it explicitly without claiming measured recovery`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(hold = 0))
            fixture.evaluate(120_000)
            fixture.configure("""{"schema_version":1,"objectives":[],"rules":[]}""")
            assertTrue((fixture.evaluate(130_000)["events"] as JsonArray).isEmpty())
            val events = takeReply(fixture.engine.history(0, 200_000))["events"] as JsonArray
            val retired = events.last().jsonObject
            assertEquals("rule_removed", retired.text("reason"))
            assertEquals("insufficient_data", retired.text("state"))
            assertEquals("true", retired.text("retired"))
        }
    }

    @Test
    fun `history retention expires old incidents explicitly and survives restart`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(hold = 0).replace("\"hold_ms\":10000", "\"hold_ms\":0"))
            repeat(160) { index ->
                fixture.duration = if (index % 2 == 0) 200.0 else 10.0
                fixture.evaluate(120_000L + index * 61_000L)
            }
            fixture.restart()
            val retained = takeReply(fixture.engine.history(0, 20_000_000))
            assertTrue((retained["events"] as JsonArray).size <= 128)
            assertTrue(requireNotNull(retained.long("expired_events")) > 0)
        }
    }
}
