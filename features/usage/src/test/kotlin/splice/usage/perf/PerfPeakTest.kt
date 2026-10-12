// NEW: V4-444 — Settings' "Most at once" was refused for the busiest commands, whose day holds more rows than one read returns.
package splice.usage.perf

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class PerfPeakTest {
    private fun row(ts: Long, total: Long, waited: Long = 0L, held: Long? = null) = PerfRow(
        ts = ts,
        outcome = "ok",
        fields = mapOf("total" to total, "admit_wait_ms" to waited) +
            listOfNotNull(held?.let { "admit_held_ms" to it }),
    )

    // Five requests overlap early in a day that holds 2,500: the newest 2,000 do not include them.
    private val day = List(5) { row(10_000L + it, total = 1_000) } +
        List(2_495) {
            // every third passed an open gate in 1 ms; only one was held behind a full limit
            row(100_000L + it * 10L, total = 1, waited = if (it % 3 == 0) 1L else 0L, held = if (it == 7) 40L else null)
        }

    private fun answer(rows: List<PerfRow>, query: String): kotlinx.serialization.json.JsonObject {
        val head = UsageHead(
            key = "claudex",
            label = "claudex",
            usage = HeadUsageSource { UsageView(0, 0, null) },
            warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
            sinks = UsageHeadSinks(perfRows = PerfRowsSource { PerfRowsWindow(rows) }),
        )
        val routes = PerfRoutes(UsageHeadLookup { listOf(head) }, WallClock { 200_000 })
        var body = ""
        testApplication {
            application { routing { get("/api/perf/turns") { routes.turns(call) } } }
            body = client.get("/api/perf/turns?head=claudex&since=0&$query").bodyAsText()
        }
        return Json.parseToJsonElement(body).jsonObject.getValue("heads").jsonArray.single().jsonObject
    }

    @Test
    fun `a day of more rows than one read returns still reports its real peak and when`() {
        val served = answer(day, "n=2000&local=0&usage=0&peak=1")

        assertTrue(served.getValue("truncated").jsonPrimitive.content.toBoolean(), "the rows are the newest 2,000 only")
        val atOnce = served.getValue("at_once").jsonObject
        assertEquals(
            "5",
            atOnce.getValue("peak").jsonPrimitive.content,
            "the five that overlapped, among the oldest rows",
        )
        assertEquals("9004", atOnce.getValue("at").jsonPrimitive.content, "the first instant the fifth was held")
        assertEquals(
            "2500",
            atOnce.getValue("counted").jsonPrimitive.content,
            "every row of the window, not the newest 2,000",
        )
        assertEquals(
            "1",
            atOnce.getValue("queued").jsonPrimitive.content,
            "the one the gate held; a 1 ms pass through an open gate is not waiting",
        )
    }

    @Test
    fun `the answer carries no peak unless it is asked for`() {
        assertEquals(null, answer(day, "n=1&usage=0")["at_once"])
    }

    @Test
    fun `a request that ends the moment the next begins is not held with it`() {
        val served = answer(listOf(row(ts = 100, total = 100), row(ts = 200, total = 100)), "peak=1")
        assertEquals("1", served.getValue("at_once").jsonObject.getValue("peak").jsonPrimitive.content)
    }
}
