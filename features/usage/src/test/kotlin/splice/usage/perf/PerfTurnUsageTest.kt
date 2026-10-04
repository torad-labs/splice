// NEW: V4-444 — route controls prove full-window usage beyond the display limit.
package splice.usage.perf

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.time.Instant

class PerfTurnUsageTest {
    private val fields = mapOf(
        PerfKeys.IN_TOKENS to 1_000L,
        PerfKeys.CACHED_TOKENS to 900L,
        PerfKeys.OUT_TOKENS to 100L,
    )
    private val priced = (0..2_500).map { i ->
        PerfRow(
            ts = 1_000L + i,
            outcome = "ok",
            fields = fields,
            model = if (i == 0) "earlier" else "m",
            account = if (i == 0) "earlier-account" else "work",
            sessionId = "synthetic-full-session",
        )
    }
    private val failure = PerfRow(
        ts = 3_600L,
        outcome = "quota-refused",
        fields = emptyMap(),
        model = "m",
        account = "spare",
        sessionId = "synthetic-full-session",
    )
    private val local = priced.first().copy(
        ts = 2_000L,
        fields = fields + (PerfKeys.LOCAL_STEP to 1L),
    )
    private val outside = listOf(priced.first().copy(ts = 999), priced.last().copy(ts = 4_000))

    private fun ApplicationTestBuilder.mount(rows: List<PerfRow>, onRead: () -> Unit = {}) {
        val catalog = ModelCatalog(
            discoveryPrefix = "synthetic--",
            models = listOf("m", "earlier").map { id ->
                ModelEntry(id, contextWindow = 100_000, rates = ModelRates(1.0, 0.1, 4.0))
            },
            defaultContextWindow = 100_000,
        )
        val source = PerfRowsSource { since ->
            onRead()
            PerfRowsWindow(rows.filter { it.ts >= since })
        }
        val head = UsageHead(
            key = "synthetic",
            label = "Synthetic",
            usage = HeadUsageSource { UsageView(0, 0, null) },
            warnPct = 80,
            warnTokens5h = 0,
            perfRows = source,
            catalog = catalog,
        )
        val routes = PerfRoutes(UsageHeadLookup { listOf(head) }, WallClock { 5_000 })
        application { routing { get("/api/perf/turns") { routes.turns(call) } } }
    }

    private fun head(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
        .getValue("heads").jsonArray.single().jsonObject

    @Test
    fun `whole-window usage precedes the row limit and excludes local steps and interval edges`() = testApplication {
        var reads = 0
        mount(priced + failure + local + outside) { reads++ }
        val response = client.get(
            "/api/perf/turns?head=synthetic&since=1000&until=4000&n=2000&local=0&time_zone=UTC",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val block = head(response.bodyAsText())
        assertEquals(1, reads)
        assertEquals(2_000, block.getValue("rows").jsonArray.size)
        assertEquals(2_502L, block.getValue("count").jsonPrimitive.long)
        val usage = block.getValue("usage").jsonObject
        val totals = usage.getValue("totals").jsonObject
        assertEquals(2_502L, totals.getValue("requests").jsonPrimitive.long)
        assertEquals(2_501_000L, totals.getValue("input_tokens").jsonPrimitive.long)
        assertEquals(2_250_900L, totals.getValue("cached_tokens").jsonPrimitive.long)
        assertEquals(250_100L, totals.getValue("output_tokens").jsonPrimitive.long)
        assertEquals(0.9, totals.getValue("cache_share").jsonPrimitive.double, 0.000001)
        assertEquals(1.47559, totals.getValue("cost_usd").jsonPrimitive.double, 0.000001)
        assertEquals(1L, totals.getValue("unpriced_requests").jsonPrimitive.long)
        val models = usage.getValue("models").jsonArray.map { it.jsonObject }
        val earlier = models.single { it.getValue("key").jsonPrimitive.content == "earlier" }
        assertEquals(1L, earlier.getValue("requests").jsonPrimitive.long)
        val accounts = usage.getValue("accounts").jsonArray.map { it.jsonObject }
        assertTrue(accounts.any { it.getValue("key").jsonPrimitive.content == "earlier-account" })
        val day = usage.getValue("days").jsonArray.single().jsonObject
        assertEquals(2_502L, day.getValue("requests").jsonPrimitive.long)
        val session = usage.getValue("sessions").jsonArray.single().jsonObject
        assertEquals("synthetic-full-session", session.getValue("key").jsonPrimitive.content)
        assertEquals(2_502L, session.getValue("requests").jsonPrimitive.long)
    }

    @Test
    fun `the same outcome filter controls both the list count and its aggregate`() = testApplication {
        mount(priced + failure + local)
        val response = client.get(
            "/api/perf/turns?head=synthetic&since=1000&until=4000&n=1&local=0&outcome=failed",
        )
        val block = head(response.bodyAsText())
        assertEquals(1L, block.getValue("count").jsonPrimitive.long)
        val totals = block.getValue("usage").jsonObject.getValue("totals").jsonObject
        assertEquals(1L, totals.getValue("requests").jsonPrimitive.long)
        assertEquals(JsonNull, totals["input_tokens"])
        assertEquals(JsonNull, totals["cost_usd"])
    }

    @Test
    fun `session models keep the latest ordinary request rather than a compactor or short tag`() = testApplication {
        val ordinary = priced.first().copy(ts = 1_500, model = "earlier")
        val compact = priced.last().copy(ts = 2_000, compact = true, model = "m")
        val legacy = priced.first().copy(sessionId = null, session = "syntheti")
        mount(listOf(ordinary, compact, legacy))
        val response = client.get("/api/perf/turns?head=synthetic&since=1000&n=1&local=0")
        val sessions = head(response.bodyAsText()).getValue("usage").jsonObject.getValue("sessions").jsonArray
        val full = sessions.map { it.jsonObject }.single { it.getValue("key") != JsonNull }
        assertEquals(2L, full.getValue("requests").jsonPrimitive.long)
        assertEquals("earlier", full.getValue("last_model").jsonPrimitive.content)
        assertEquals(1_500L, full.getValue("last_model_ts_epoch_ms").jsonPrimitive.long)
        val unassigned = sessions.map { it.jsonObject }.single { it.getValue("key") == JsonNull }
        assertEquals(1L, unassigned.getValue("requests").jsonPrimitive.long)
    }

    @Test
    fun `day groups use the requested viewer zone and a misspelled zone is refused`() = testApplication {
        val first = Instant.parse("2026-10-03T06:59:00Z").toEpochMilli()
        val second = Instant.parse("2026-10-03T07:01:00Z").toEpochMilli()
        mount(listOf(priced.first().copy(ts = first), priced.last().copy(ts = second)))
        val response = client.get(
            "/api/perf/turns?head=synthetic&since=0&time_zone=America%2FLos_Angeles&local=0",
        )
        val days = head(response.bodyAsText()).getValue("usage").jsonObject.getValue("days").jsonArray
        assertEquals(
            listOf("2026-10-02", "2026-10-03"),
            days.map { it.jsonObject.getValue("key").jsonPrimitive.content },
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/perf/turns?head=synthetic&since=0&time_zone=wrong-zone").status,
        )
    }
}
