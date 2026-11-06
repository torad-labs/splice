package splice.usage.perf.query

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.JsonScalars
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.UsageHeads
import splice.usage.perf.PerfPayloads
import splice.usage.perf.PerfRoutes
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class PerfLegacySelectorsTest {
    private val head = UsageHead(
        "h",
        "h",
        HeadUsageSource { UsageView(0, 0, null) },
        UsageHeadWarn(80, 0),
        sinks = UsageHeadSinks(perfRows = PerfRowsSource { PerfRowsWindow(emptyList()) }),
    )

    @Test
    fun `legacy roots survive and unsupported selectors are refused`() = testApplication {
        val payloads = PerfPayloads(UsageHeads { listOf(head) }) { 100_000_000 }
        val turns = PerfRoutes(UsageHeadLookup { listOf(head) }) { 100_000_000 }
        application {
            routing {
                get("/summary") { payloads.summary(call) }
                get("/turns") { turns.turns(call) }
            }
        }
        listOf("/summary", "/turns?head=h").forEach { path ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status)
            val value = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("h", JsonScalars.str(value.getValue("heads").jsonArray.single().jsonObject, "key"))
            assertTrue("contract_version" !in value)
        }
        listOf(
            "/summary?model=m",
            "/summary?session=s",
            "/turns?head=h&transport=mcp",
            "/turns?head=h&streaming=true",
            "/turns?head=h&ask=transport%3Dmcp",
        ).forEach { path ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val value = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unsupported", JsonScalars.str(value, "status"))
            assertTrue("heads" !in value)
        }
    }
}
