package splice.usage.alerts

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.objectives.PerfObjectiveStore
import splice.usage.perf.objectives.objectiveSettings
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class PerfWebhookDeliveryTest {
    @Test
    fun `queued performance delivery is disabled when settings change before the worker sends`(
        @TempDir tmp: Path,
    ) = runTest {
        val bodies = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                bodies.add((request.body as TextContent).text)
                respond("", HttpStatusCode.OK)
            },
        ).use { client ->
            PerfObjectiveStore(tmp.resolve("objectives.json")).use { objectives ->
                val configured = objectives.update(
                    Json.parseToJsonElement(objectiveSettings(delivery = true)).jsonObject,
                )
                val revision = requireNotNull(JsonScalars.str(configured, "revision"))
                PerfWorkBudget.release(configured)
                val alerts = AlertStore(tmp.resolve("alerts.json"))
                alerts.replace(AlertSettings(webhookUrl = "https://example.invalid/hook"))
                PerfWebhookDelivery(AlertDelivery(alerts, { }, this, client), this, objectives).use { delivery ->
                    val queued = JsonObject(event(true) + ("settings_revision" to JsonPrimitive(revision)))
                    assertTrue(delivery.enqueue(queued))
                    val changed = objectives.update(
                        Json.parseToJsonElement(objectiveSettings(delivery = false)).jsonObject,
                    )
                    PerfWorkBudget.release(changed)
                    runCurrent()
                    assertEquals("disabled", delivery.result("dedup"))
                    assertTrue(bodies.isEmpty())
                }
            }
        }
    }

    @Test
    fun `performance opt in and existing preferences gate the shared text webhook transport`(
        @TempDir tmp: Path,
    ) = runTest {
        val bodies = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                bodies.add((request.body as TextContent).text)
                respond("", HttpStatusCode.ServiceUnavailable)
            },
        ).use { client ->
            val store = AlertStore(tmp.resolve("alerts.json"))
            store.replace(AlertSettings(desktop = true, webhookUrl = "https://example.invalid/private-secret"))
            val logs = mutableListOf<String>()
            PerfWebhookDelivery(AlertDelivery(store, { logs.add(it) }, this, client), this).use { delivery ->
                assertFalse(delivery.enqueue(event(false)))
                assertTrue(bodies.isEmpty())
                assertTrue(delivery.enqueue(event(true)))
                assertTrue(delivery.enqueue(event(true)))
                runCurrent()
                assertEquals(1, bodies.size)
                assertEquals("failed", delivery.result("dedup"))
                assertEquals("1", JsonScalars.str(delivery.status(), "failed"))
                val body = Json.parseToJsonElement(bodies.first()).jsonObject
                assertEquals(setOf("text"), body.keys)
                assertTrue(JsonScalars.str(body, "text").orEmpty().contains("Performance rule slow firing"))
                assertFalse(logs.joinToString().contains("private-secret"))
                store.replace(AlertSettings(desktop = true))
                assertTrue(delivery.enqueue(event(true)))
                runCurrent()
                assertEquals("disabled", delivery.result("dedup"))
                assertEquals(1, bodies.size)
            }
        }
    }

    private fun event(enabled: Boolean): JsonObject = buildJsonObject {
        put("dedup_key", "dedup")
        put("rule_id", "slow")
        put("metric", "histograms.arrival_to_terminal_ms.max")
        put("state", "firing")
        put("observed", 200)
        put("threshold", 100)
        put("delivery_enabled", enabled)
    }
}
