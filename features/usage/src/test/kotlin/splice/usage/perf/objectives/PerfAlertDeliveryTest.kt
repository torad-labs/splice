package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PerfAlertDeliveryTest {
    @Test
    fun `actual failed deliveries retry at bounded times and differ from enqueue failures`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(delivery = true, hold = 0))
            assertEquals("accepted", fixture.evaluate(120_000).event().text("delivery_status"))
            fixture.delivery.completion = "failed"
            assertEquals("retry_pending", fixture.evaluate(121_000).event().text("delivery_status"))
            assertEquals(1, fixture.delivery.events.size)
            fixture.evaluate(140_000)
            fixture.evaluate(141_000)
            fixture.evaluate(170_000)
            val result = fixture.evaluate(171_000)
            assertEquals("failed", result.event().text("delivery_status"))
            assertEquals(3L, result.long("delivery_failures"))
            assertEquals(0L, result.long("enqueue_failures"))
            assertEquals(3, fixture.delivery.events.size)
            fixture.evaluate(200_000)
            assertEquals(3, fixture.delivery.events.size)
            val attempts = result["attempts"] as JsonArray
            assertEquals(3L, attempts.first().jsonObject.long("attempts"))
        }
    }

    @Test
    fun `queue refusal exhausts without being counted as actual delivery failure`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(delivery = true, hold = 0))
            fixture.delivery.accepts = false
            fixture.evaluate(120_000)
            fixture.evaluate(140_000)
            val result = fixture.evaluate(170_000)
            assertEquals("enqueue_exhausted", result.event().text("delivery_status"))
            assertEquals(0L, result.long("delivery_failures"))
            assertEquals(3L, result.long("enqueue_failures"))
        }
    }

    @Test
    fun `failed lifecycle persistence never accepts notification and does not publish firing`(@TempDir tmp: Path) {
        ObjectiveFixture(tmp).use { fixture ->
            fixture.configure(objectiveSettings(delivery = true, hold = 0))
            Files.createDirectory(tmp.resolve("objectives.json.alerts"))
            val result = fixture.evaluate(120_000)
            assertEquals("partial", result.text("status"))
            assertEquals("lifecycle_write_failed", result.text("reason"))
            assertTrue((result["events"] as JsonArray).isEmpty())
            assertTrue(fixture.delivery.events.isEmpty())
        }
    }
}
