// NEW: V4-444 — a code-mode step is counted APART from the requests in the request list's totals, and carries no
// price reason.
//
// A step is a reply splice writes itself, with nothing sent to a provider. The window summary already counted
// steps apart (PerfSummary.local_steps); these totals counted each one as a request, as a request missing its
// input and output (a step has no token counts), and as one with no price for a reason no case covered — so a
// window with steps reported requests that never reached a provider as unpriced and incomplete.
//
// Driven as a DIFFERENCE over one window: the same rows read with the steps left out (local=0) and with them in
// (local=1, the default) must agree on every figure the totals report but the steps' own count.
package splice.usage.perf

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadStatusline
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

private const val NOW = 5_000L
private const val SESSION = "step-session"

class PerfLocalStepTotalsTest {
    private val billed = mapOf(
        PerfKeys.IN_TOKENS to 1_000L,
        PerfKeys.CACHED_TOKENS to 900L,
        PerfKeys.OUT_TOKENS to 100L,
        PerfKeys.CACHE_WRITE_TOKENS to 0L,
    )

    private fun row(ts: Long, fields: Map<String, Long>, outcome: String = "ok") = PerfRow(
        ts = ts,
        outcome = outcome,
        fields = fields,
        facts = PerfTurnFacts(model = "m", account = "work"),
        transcript = PerfTranscriptLink(sessionId = SESSION),
    )

    /** A real code-mode step, as TurnFinish records one: no upstream post, so no token counts at all. */
    private val step = row(2_000L, mapOf(PerfKeys.LOCAL_STEP to 1L, PerfKeys.NO_REQUEST to 1L))
    private val requests = listOf(
        row(1_000L, billed),
        row(1_500L, billed),
        // A model with no rate card: an unpriced request, so the step cannot hide inside an all-priced window.
        row(1_800L, billed).let { it.copy(facts = it.facts.copy(model = "free")) },
    )

    private fun ApplicationTestBuilder.mount(rows: List<PerfRow>) {
        val catalog = ModelCatalog(
            discoveryPrefix = "synthetic--",
            models = listOf(
                ModelEntry("m", contextWindow = 100_000, rates = ModelRates(1.0, 0.1, 4.0)),
                ModelEntry("free", contextWindow = 100_000),
            ),
            defaultContextWindow = 100_000,
        )
        val head = UsageHead(
            key = "synthetic",
            label = "Synthetic",
            usage = HeadUsageSource { UsageView(0, 0, null) },
            warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
            sinks = UsageHeadSinks(
                perfRows = PerfRowsSource { since -> PerfRowsWindow(rows.filter { it.ts >= since }) },
            ),
            statusline = UsageHeadStatusline(catalog = catalog),
        )
        val lookup = object : UsageHeadLookup {
            override fun byName(name: String): List<UsageHead> = listOf(head)
        }
        val routes = PerfRoutes(lookup, WallClock { NOW })
        application { routing { get("/api/perf/turns") { routes.turns(call) } } }
    }

    private fun block(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
        .getValue("heads").jsonArray.single().jsonObject

    private fun totals(block: JsonObject): JsonObject =
        block.getValue("usage").jsonObject.getValue("totals").jsonObject

    @Test
    fun `a step is counted apart from the requests and moves no other figure in the totals`() = testApplication {
        mount(requests + step)
        val without = block(client.get("$TURNS&local=0").bodyAsText())
        val with = block(client.get("$TURNS&local=1").bodyAsText())

        assertEquals(
            JsonObject(totals(without) - "local_steps"),
            JsonObject(totals(with) - "local_steps"),
            "including the step moves no figure the totals report",
        )
        assertEquals(0L, totals(without).getValue("local_steps").jsonPrimitive.long)
        assertEquals(1L, totals(with).getValue("local_steps").jsonPrimitive.long, "the step is counted, apart")
        assertEquals(
            requests.size.toLong(),
            totals(with).getValue("requests").jsonPrimitive.long,
            "a step is not a request: it asked no provider",
        )
        assertEquals(
            without.getValue("count").jsonPrimitive.long + 1,
            with.getValue("count").jsonPrimitive.long,
            "the list still holds the step: counted apart, not left out",
        )
    }

    @Test
    fun `a step carries no price and no reason for having none`() = testApplication {
        mount(requests + step)
        val rows = block(client.get("$TURNS&local=1").bodyAsText()).getValue("rows").jsonArray
            .map { it.jsonObject }
        val steps = rows.filter { it[PerfKeys.LOCAL_STEP]?.jsonPrimitive?.long == 1L }

        assertEquals(1, steps.size, "the step is in the list")
        assertEquals(JsonNull, steps.single().getValue("cost_usd"), "nothing was sent, so there is no price")
        assertEquals(
            JsonNull,
            steps.single().getValue("cost_reason"),
            "and no reason either: 'uncounted' read as a request splice could not price rather than never sent",
        )
        val sent = rows.filterNot { it[PerfKeys.LOCAL_STEP]?.jsonPrimitive?.long == 1L }
        assertEquals(
            listOf(JsonNull, JsonNull),
            sent.filter { it.getValue("model").jsonPrimitive.content == "m" }.map { it.getValue("cost_reason") },
            "a request with a price reports no reason, which is the other way a reason is absent",
        )
        val undeclared = sent.single { it.getValue("model").jsonPrimitive.content == "free" }
        assertEquals(
            "undeclared",
            undeclared.getValue("cost_reason").jsonPrimitive.content,
            "a request splice really could not price still says why, so the step's silence is not a blanket",
        )
    }
}

private const val TURNS = "/api/perf/turns?head=synthetic&since=1000&n=2000&time_zone=UTC"
