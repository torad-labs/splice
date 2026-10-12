// NEW: Oct 11, 2026 — every request row names its project, in the object the session rows carry, and `?project=<id>`
// takes that object's id. A row whose session no project holds says null, so a screen never invents one.
package splice.usage.perf

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class PerfProjectTagTest {
    private fun row(ts: Long, session: String?) = PerfRow(
        ts = ts,
        outcome = "ok",
        fields = emptyMap(),
        facts = PerfTurnFacts(model = "opus", session = session, account = "work"),
    )

    private val rows = listOf(row(1_000, "aaaaaaaa"), row(2_000, "bbbbbbbb"), row(3_000, "cccccccc"), row(4_000, null))
    private val mythos = buildJsonObject {
        put("id", "/p/mythos")
        put("name", "mythos")
        put("label", "mythos")
        put("source", "folder")
    }
    private val tags = object : ProjectTags {
        private val byTag = mapOf("aaaaaaaa" to mythos, "bbbbbbbb" to mythos)

        override fun of(tag: String): JsonObject? = byTag[tag]

        override fun tagsOf(id: String): Set<String> =
            byTag.filterValues { it.getValue("id").jsonPrimitive.content == id }.keys

        override fun titleOf(tag: String): String? = mapOf("aaaaaaaa" to "fix the billing rounding")[tag]
    }
    private val head = UsageHead(
        key = "kimi",
        label = "claude-kimi",
        usage = HeadUsageSource { UsageView(0, 0, null) },
        warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
        sinks = UsageHeadSinks(perfRows = PerfRowsSource { since -> PerfRowsWindow(rows.filter { it.ts >= since }) }),
    )
    private val routes = PerfRoutes(UsageHeadLookup { listOf(head) }, WallClock { 20_000 })
        .also { it.projects = RequestProjects { tags } }

    private suspend fun io.ktor.client.HttpClient.rows(query: String): List<JsonObject> {
        val body = Json.parseToJsonElement(get("/api/perf/turns?head=kimi&since=0&$query").bodyAsText()).jsonObject
        return body.getValue("heads").jsonArray.single().jsonObject.getValue("rows").jsonArray.map { it.jsonObject }
    }

    @Test
    fun `project none lists the requests no project holds, the ones a row prints as null`() = testApplication {
        application { routing { get("/api/perf/turns") { routes.turns(call) } } }
        val none = client.rows("project=none")
        assertEquals(listOf(3_000L, 4_000L), none.map { it.getValue("ts").jsonPrimitive.content.toLong() })
        assertEquals(listOf(JsonNull, JsonNull), none.map { it.getValue("project") })
    }

    @Test
    fun `rows carry the session rows project object, null where none holds the session, and the filter takes its id`() =
        testApplication {
            application { routing { get("/api/perf/turns") { routes.turns(call) } } }
            val all = client.rows("")
            assertEquals(listOf(mythos, mythos, JsonNull, JsonNull), all.map { it.getValue("project") })
            val only = client.rows("project=%2Fp%2Fmythos")
            assertEquals(listOf(1_000L, 2_000L), only.map { it.getValue("ts").jsonPrimitive.content.toLong() })
            assertEquals(emptyList<JsonObject>(), client.rows("project=%2Fp%2Fnothing"))
        }

    @Test
    fun `a row carries the title of its session, null where the session has a name or none is known`() =
        testApplication {
            application { routing { get("/api/perf/turns") { routes.turns(call) } } }
            val titles = client.rows("").map { it.getValue("title") }
            assertEquals(listOf(JsonPrimitive("fix the billing rounding"), JsonNull, JsonNull, JsonNull), titles)
        }
}
