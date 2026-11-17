// A report's reference cost (docs/specs/server-mode.md, F5): what the same tokens would have cost at
// `[server.reference]`, each row priced on its own and the group's rows summed, a cached token at the input rate when
// the card names no cached rate.
package splice.server.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerReference
import splice.core.topology.ServerTeams
import splice.server.ServerPeople
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

internal class UsageReportTest {
    @TempDir lateinit var folder: Path

    private val window = UsageWindow(Instant.parse("2026-11-16T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"))

    private fun row(prompt: Long, cached: Long, completion: Long) = LedgerRow(
        at = "2026-11-16T14:00:00Z",
        person = "ann@example.com",
        device = "spk_test",
        command = "claude-team",
        route = ROUTE_MESSAGES,
        status = 200,
        outcome = "ok",
        promptTokens = prompt,
        cachedTokens = cached,
        completionTokens = completion,
        durationMs = 40,
    )

    private fun referenceCost(reference: ServerReference): Double {
        val people = ServerPeople(folder.resolve("people.json"), Dispatchers.IO)
        val report = UsageReport(people, ServerTeams(), reference, ZoneOffset.UTC)
        val rows = listOf(row(prompt = 1_000_000, cached = 400_000, completion = 100_000), row(2_000_000, 0, 0))
        val group = report.of(rows, window, ReportBy.PERSON, emptyList())["groups"]!!.jsonArray.single().jsonObject
        return group["reference_cost_usd"]!!.jsonPrimitive.double
    }

    @Test
    fun `each row is priced at the reference card and the group sums them`() {
        val card = ServerReference(inputUsdPerMtok = 3.0, cachedUsdPerMtok = 0.3, outputUsdPerMtok = 15.0)
        // 0.6M fresh at $3, 0.4M cached at $0.30, 0.1M out at $15; then 2M fresh at $3.
        assertEquals(1.8 + 0.12 + 1.5 + 6.0, referenceCost(card), 1e-9)
    }

    private fun totals(reference: ServerReference, fixed: List<FixedCost>): JsonObject {
        val people = ServerPeople(folder.resolve("people.json"), Dispatchers.IO)
        val report = UsageReport(people, ServerTeams(), reference, ZoneOffset.UTC)
        val rows = listOf(
            row(prompt = 1_000_000, cached = 0, completion = 0).copy(costUsd = 2.0),
            row(prompt = 0, cached = 0, completion = 1_000_000).copy(person = "bo@example.com", costUsd = 8.0),
            row(prompt = 0, cached = 0, completion = 0).copy(outcome = "refused"),
        )
        val answer = report.of(rows, window, ReportBy.PERSON, fixed)
        assertEquals(JsonArray(fixed.map { it.json() }), answer["fixed"])
        return answer["totals"]!!.jsonObject
    }

    @Test
    fun `the totals add the hours of a provider paid by the hour to the rate cards' cost, beside the reference`() {
        val card = ServerReference(inputUsdPerMtok = 3.0, cachedUsdPerMtok = 0.3, outputUsdPerMtok = 15.0)
        val totals = totals(card, listOf(FixedCost("box", hours = 1.0, costUsd = 2.5)))
        assertEquals(10.0, totals["cost_usd"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(2.5, totals["fixed_usd"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(12.5, totals["actual_usd"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(3.0 + 15.0, totals["reference_cost_usd"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `with no provider paid by the hour the totals carry no fixed cost, and no reference while none is set`() {
        val totals = totals(ServerReference(), emptyList())
        assertEquals(0.0, totals["fixed_usd"]!!.jsonPrimitive.double)
        assertEquals(10.0, totals["actual_usd"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(JsonNull, totals["reference_cost_usd"])
    }

    @Test
    fun `a card with no cached rate prices a cached token at the input rate`() {
        val card = ServerReference(inputUsdPerMtok = 3.0, outputUsdPerMtok = 15.0)
        assertEquals(1.8 + 1.2 + 1.5 + 6.0, referenceCost(card), 1e-9)
    }
}
