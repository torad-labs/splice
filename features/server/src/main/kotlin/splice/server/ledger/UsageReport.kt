// NEW: server mode (docs/specs/server-mode.md, F5, ledger-reports). Who used what, and what it cost: the ledger's rows
// over a window, grouped by person, by team (the one each row ran under, or each person's team today), by day (on the
// server's clock), by model or by device. Each group counts its requests, its errors (every request that was not
// served, apart from those whose client left) and the clients that left, sums its tokens, prices them at the models'
// rate cards and, when `[server.reference]` is set, at the rates a hosted model would have charged for the same tokens.
package splice.server.ledger

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.model.ModelRates
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.topology.ServerReference
import splice.core.topology.ServerTeams
import splice.server.ServerPeople
import java.time.Instant
import java.time.ZoneId

/** One group of a report, in the order its columns are printed. */
internal data class UsageGroup(
    val key: String,
    val requests: Int,
    val errors: Int,
    val clientGone: Int,
    val tokens: ReportTokens,
    val costUsd: Double?,
    val referenceCostUsd: Double?,
)

/** The tokens a group used: every prompt token, the share of them read from cache, and the answers'. */
internal data class ReportTokens(val prompt: Long, val cached: Long, val completion: Long)

/** How a report is grouped: by what each row is keyed under. */
internal enum class ReportBy(public val wire: String) {
    PERSON("person"),
    TEAM("team"),
    CURRENT_TEAM("current-team"),
    DAY("day"),
    MODEL("model"),
    DEVICE("device"),
}

internal class UsageReport(
    private val people: ServerPeople,
    private val teams: ServerTeams,
    reference: ServerReference,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val cost = TokenCost()

    // `[server.reference]` as a rate card: a cached token at the input rate when it names no cached rate of its own.
    private val referenceRates: ModelRates? = reference.takeIf { it.declared }?.let {
        val input = it.inputUsdPerMtok ?: 0.0
        ModelRates(input = input, cacheRead = it.cachedUsdPerMtok ?: input, output = it.outputUsdPerMtok ?: 0.0)
    }

    /** [rows], which ran in [window], grouped [by], beside what the providers paid for by the hour cost over it
     *  ([fixed]), as `{"window": {"from", "to"}, "by", "groups": [...], "fixed": [...], "totals": {...}}`. */
    fun of(rows: List<LedgerRow>, window: UsageWindow, by: ReportBy, fixed: List<FixedCost>): JsonObject {
        val groups = rows.groupBy { keyOf(it, by) }.toSortedMap().map { (key, grouped) -> group(key, grouped) }
        return buildJsonObject {
            put("window", window.json())
            put("by", by.wire)
            put("groups", buildJsonArray { groups.forEach { add(UsageTables.json(it)) } })
            put("fixed", buildJsonArray { fixed.forEach { add(it.json()) } })
            put("totals", totals(rows, fixed))
        }
    }

    /** The window's whole cost: `cost_usd` at the rate cards, `fixed_usd` for the hours of the providers paid by the
     *  hour, `actual_usd` the two together, which is what the team paid, and `reference_cost_usd` beside them, null
     *  while `[server.reference]` is not set, as each group's is. */
    private fun totals(rows: List<LedgerRow>, fixed: List<FixedCost>): JsonObject {
        val card = rows.sumOf { it.costUsd ?: 0.0 }
        val hourly = fixed.sumOf { it.costUsd }
        return buildJsonObject {
            put("cost_usd", card)
            put("fixed_usd", hourly)
            put("actual_usd", card + hourly)
            put("reference_cost_usd", referenceRates?.let { rates -> rows.sumOf { referenceUsd(it, rates) } })
        }
    }

    private fun keyOf(row: LedgerRow, by: ReportBy): String = when (by) {
        ReportBy.PERSON -> row.person
        ReportBy.TEAM -> teamKey(row.team)
        ReportBy.CURRENT_TEAM -> teamKey(people.team(row.person, teams))
        ReportBy.DAY -> Instant.parse(row.at).atZone(zone).toLocalDate().toString()
        ReportBy.MODEL -> row.model ?: UNNAMED_MODEL
        ReportBy.DEVICE -> row.device
    }

    private fun teamKey(team: String?): String = team ?: NO_TEAM

    private fun group(key: String, rows: List<LedgerRow>): UsageGroup {
        val tokens = ReportTokens(
            prompt = rows.sumOf { it.promptTokens },
            cached = rows.sumOf { it.cachedTokens },
            completion = rows.sumOf { it.completionTokens },
        )
        val costs = rows.mapNotNull { it.costUsd }
        return UsageGroup(
            key = key,
            requests = rows.size,
            errors = rows.count { RowOutcome.isError(it.outcome) },
            clientGone = rows.count { it.outcome == Outcome.CLIENT_GONE.wire },
            tokens = tokens,
            costUsd = costs.takeIf { it.isNotEmpty() }?.sum(),
            referenceCostUsd = referenceRates?.let { rates -> rows.sumOf { referenceUsd(it, rates) } },
        )
    }

    /** What [row]'s tokens would have cost at `[server.reference]`: each row priced on its own, then summed, the way
     *  its own cost is, so the two columns weigh tokens alike. */
    private fun referenceUsd(row: LedgerRow, rates: ModelRates): Double {
        val buckets = TokenBuckets(
            input = row.promptTokens - row.cachedTokens,
            cacheRead = row.cachedTokens,
            output = row.completionTokens,
        )
        return cost.of(buckets, rates)
    }
}

// The key of the rows of a person with no team, and of a request refused before it named a model.
private const val NO_TEAM = "(no team)"
private const val UNNAMED_MODEL = "(no model)"
