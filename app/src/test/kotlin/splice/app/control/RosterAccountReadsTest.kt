// Through /api/perf/turns as Usage and Requests read it, with the names RosterAccountNames gives: thousands of rows that
// share a few recorded logins read their labels right, and a signed-out login never shows its email.
package splice.app.control

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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.signin.LoginStatus
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.perf.PerfRoutes
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.PerfTurnFacts
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

private const val HEAD = "claude"
private const val REF = "1a2b3c4d"
private const val PROVED = "proved-work"
private const val GONE = "gone@example.invalid"
private const val ROWS = 3_000

class RosterAccountReadsTest {
    /** The native login owner: a proved login reads its label, any other its recorded name. */
    private val places = object : ClaudeLoginPlaces {
        override fun places(): List<ClaudeLoginPlaceView> = emptyList()
        override suspend fun login(place: ClaudeLoginPlaceId, label: String?): LoginStatus = error("no login here")
        override fun poll(id: String): LoginStatus? = null
        override suspend fun submit(id: String, code: String): Boolean = false
        override fun hasCarryingProof(head: String, session: String): Boolean = false
        override fun accountLabel(head: String, account: String): String = if (account == PROVED) "work" else account
    }

    private val roster = AccountRoster {
        """{"accounts":[{"ref":"$REF","display_name":"Work","heads":["$HEAD"],"account_labels":{"$HEAD":"work"}}]}"""
    }

    private val recorded = listOf("work", PROVED, GONE)
    private val head = UsageHead(
        key = HEAD,
        label = "claude-splice",
        usage = HeadUsageSource { UsageView(0, 0, null) },
        warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
        sinks = UsageHeadSinks(
            perfRows = PerfRowsSource {
                PerfRowsWindow(
                    (1..ROWS).map { i ->
                        PerfRow(
                            ts = i.toLong(),
                            outcome = "ok",
                            fields = mapOf("total" to 5L),
                            facts = PerfTurnFacts(model = "m", account = recorded[i % recorded.size]),
                        )
                    },
                )
            },
        ),
    )

    private val routes = PerfRoutes(
        UsageHeadLookup { name -> if (name == HEAD) listOf(head) else emptyList() },
        WallClock { 10_000 },
    ).apply { accounts = RosterAccountNames(roster, logins = { places }) }

    @Test
    fun `thousands of rows on three logins read their labels, and a signed-out one shows no email`() =
        testApplication {
            application { routing { get("/api/perf/turns") { routes.turns(call) } } }

            val body = client.get("/api/perf/turns?head=$HEAD&since=0&n=$ROWS").bodyAsText()
            val answer = Json.parseToJsonElement(body).jsonObject.getValue("heads").jsonArray.single().jsonObject
            val groups = answer.getValue("usage").jsonObject.getValue("accounts").jsonArray.map { it.jsonObject }
                .associate { it.getValue("key").jsonPrimitive.content to it.getValue("label").jsonPrimitive.content }

            assertEquals("Work", groups[REF], "the pool label and the proved login are the one roster login")
            assertEquals(2, groups.size, "the roster login and the signed-out one, each one group")
            assertFalse(GONE in body, "a signed-out login never shows its email")
        }
}
