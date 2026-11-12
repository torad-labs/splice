// NEW: labels only (Eli's ruling via splice-lead, 2026-10-12). Through /api/perf/turns as Requests and Usage read it:
// a login is shown by its label and grouped and filtered by its ref, rows written with an email before refs existed
// read as that same login, the files are never rewritten, and a key keeps the name it was recorded under.
package splice.usage.perf

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

private const val REF = "1a2b3c4d"
private const val OLD_EMAIL = "ops@example.invalid"
private const val KEY = "OPENAI_API_KEY:9f8e7d6c"

class PerfAccountLabelsTest {
    private fun row(ts: Long, account: String) =
        PerfRow(
            ts = ts,
            outcome = "ok",
            fields = mapOf("total" to 5L),
            facts = PerfTurnFacts(model = "m", account = account),
        )

    private val head = UsageHead(
        key = "claude",
        label = "claude-splice",
        usage = HeadUsageSource { UsageView(0, 0, null) },
        warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
        sinks = UsageHeadSinks(
            perfRows = PerfRowsSource {
                PerfRowsWindow(listOf(row(1_000, OLD_EMAIL), row(2_000, REF), row(3_000, KEY)))
            },
        ),
    )

    private val routes = PerfRoutes(
        UsageHeadLookup { name ->
            if (name == "claude") {
                listOf(
                    head,
                )
            } else {
                emptyList()
            }
        },
        WallClock {
            5_000
        },
    ).apply {
        accounts = RecordedAccounts {
            HeadAccountNames { recorded ->
                NamedAccount(
                    REF,
                    "Work",
                ).takeIf { recorded == OLD_EMAIL || recorded == REF }
            }
        }
    }

    private suspend fun HttpClient.answer(query: String): Pair<String, JsonObject> {
        val body = get("/api/perf/turns?head=claude&since=0&$query").bodyAsText()
        return body to Json.parseToJsonElement(body).jsonObject.getValue("heads").jsonArray.single().jsonObject
    }

    @Test
    fun `a login reads its label, groups and filters by its ref, and an old email row is it`() = testApplication {
        application { routing { get("/api/perf/turns") { routes.turns(call) } } }

        val (body, all) = client.answer("n=10")
        assertFalse(OLD_EMAIL in body, "no page is handed the email an old row recorded")
        val rows = all.getValue("rows").jsonArray.map { it.jsonObject }
        assertEquals(listOf(REF, REF, KEY), rows.map { it.getValue("account").jsonPrimitive.content })
        assertEquals("Work", rows.first().getValue("account_label").jsonPrimitive.content)
        val groups = all.getValue("usage").jsonObject.getValue("accounts").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("key").jsonPrimitive.content }
        assertEquals(setOf(REF, KEY), groups.keys, "one login is one group, whatever its rows recorded")
        assertEquals("Work", groups.getValue(REF).getValue("label").jsonPrimitive.content)

        val (_, filtered) = client.answer("n=10&account=$REF")
        assertEquals(
            2,
            filtered.getValue("count").jsonPrimitive.content.toInt(),
            "the ref finds the row written before refs",
        )
        val (_, key) = client.answer("n=10&account=$KEY")
        assertEquals(
            1,
            key.getValue("count").jsonPrimitive.content.toInt(),
            "a key is still found by its recorded name",
        )
    }
}
