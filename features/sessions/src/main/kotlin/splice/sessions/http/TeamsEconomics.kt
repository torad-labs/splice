// NEW: V4-160 — the team economics, moved verbatim out of TeamsRoutes.kt (concentration, 2026-09-18).
// TeamsRoutes.kt's header states what the economics count and why.
package splice.sessions.http

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.model.ModelCatalog
import splice.core.model.TokenCost
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.sessions.query.SessionAccountPlans
import splice.sessions.query.SessionHead
import splice.sessions.query.SessionPerfRow
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot

/** The perf row's session tag width (TurnDrive.SESSION_TAG_CHARS). */
private const val PERF_TAG_CHARS = 8

/** Lifetime economics for one team over the perf files of every head its slots name. */
internal class TeamEconomics(private val team: Team, private val heads: Map<String, SessionHead>) {
    private val slotOfTag = team.slots
        .flatMap { slot -> held(slot).map { it.take(PERF_TAG_CHARS) to slot } }
        .distinctBy { it.first }
        .toMap()
    private val bySlot = team.slots.associate { it.id to PerfTally() }
    private var oldest: Long? = null

    fun json(): JsonObject {
        val read = team.slots.map { it.head }.distinct().filter { it in heads }
        read.forEach { tally(heads.getValue(it)) }
        return buildJsonObject {
            put("oldest_turn_epoch_millis", oldest)
            put(
                "slots",
                buildJsonArray {
                    bySlot.forEach { (slot, tally) ->
                        add(tally.json { it.put("slot", slot) })
                    }
                },
            )
        }
    }

    /** Every session [slot] ever held, the current one included. */
    private fun held(slot: TeamSlot): List<String> = slot.sessionsHistory + listOfNotNull(slot.session)

    /** The team's lifetime starts at its creation: a turn before it is no team's turn, even one a
     *  later-bound session made. The cutoff is also what keeps this read bounded now that the perf
     *  history reaches back through the archive (PerfRowsFileSource skips generations older than it);
     *  a team made before teams recorded a creation time reads from 0, as it always did. */
    private fun tally(head: SessionHead) {
        val window = head.perfRows?.window(team.createdAt) ?: return
        oldest = listOfNotNull(oldest, window.oldestHeldTs ?: window.rows.minOfOrNull { it.ts }).minOrNull()
        for (row in window.rows) {
            if (row.fields[PerfKeys.LOCAL_STEP] == 1L) continue // No upstream turn to attribute to this team.
            row.session?.let(slotOfTag::get)?.let { slot ->
                bySlot.getValue(slot.id).add(row, head.catalog, head.plans)
            }
        }
    }
}

/** How a tally's JSON names the slot it covers. */
internal fun interface TallyLabel {
    operator fun invoke(into: JsonObjectBuilder)
}

/** Turns, token buckets and dollars over perf rows, each row priced against its own head's catalog
 *  with the SessionCost arithmetic. A turn whose model has no rate card adds no dollars and is counted
 *  in [unpricedTurns] instead: V4-221's rule, the console's costOf. */
internal class PerfTally(private val cost: TokenCost = TokenCost()) {
    var turns: Long = 0L
        private set

    /** Counted turns whose model had no rate card, so their dollars are not in [costUsd]. */
    var unpricedTurns: Long = 0L
        private set

    /** Counted turns that moved any tokens at all: a refused or cut turn moves none and belongs to no money share. */
    var movedTurns: Long = 0L
        private set

    /** The moved turns that were priced, so "dollars for N" counts exactly the requests those dollars cover. */
    var pricedMovedTurns: Long = 0L
        private set
    private var input = 0L
    private var cacheRead = 0L
    private var cacheWrite = 0L
    private var output = 0L
    private var usd = 0.0

    /** Moved turns an account with a plan carried: they are counted in [unpricedTurns] and never in [pricedMovedTurns],
     *  because a plan covered them (fin, Oct 11). */
    var planTurns: Long = 0L
        private set

    /** The priced turns' dollars, or null when there are turns and none was priced. V4-264: one turn
     *  on a model with no card (the client's haiku call) left take 3's lead with no figure at all. */
    val costUsd: Double? get() = usd.takeUnless { turns > 0L && unpricedTurns == turns }

    fun add(row: SessionPerfRow, catalog: ModelCatalog?, plans: SessionAccountPlans = SessionAccountPlans { false }) {
        // Every request the row billed: its final round and the rounds it absorbed (TurnBill).
        val buckets = TurnBill.total(row.fields)
        turns += 1
        input += buckets.input
        cacheRead += buckets.cacheRead
        cacheWrite += buckets.cacheWrite
        output += buckets.output
        val amount = TurnBill.usd(row.fields, rates(row.model, catalog)?.at(row.ts), cost)
        val moved = buckets.input + buckets.cacheRead + buckets.cacheWrite + buckets.output > 0L
        val onPlan = plans(row.account)
        file(amount, moved, onPlan)
        if (moved) {
            movedTurns += 1
            if (amount != null && !onPlan) pricedMovedTurns += 1
        }
    }

    /** A plan covered the request: its dollars are a value, never a cost, and it stays out of the priced share. */
    private fun file(amount: Double?, moved: Boolean, onPlan: Boolean) {
        when {
            onPlan -> {
                unpricedTurns += 1
                if (moved) planTurns += 1
            }
            amount == null -> unpricedTurns += 1
            else -> usd += amount
        }
    }

    fun json(label: TallyLabel): JsonObject = buildJsonObject {
        label(this)
        put("turns", turns)
        put(
            "tokens",
            buildJsonObject {
                put("input", input)
                put("cache_read", cacheRead)
                put("cache_write", cacheWrite)
                put("output", output)
            },
        )
        put("cost_usd", costUsd)
        put("plan_turns", planTurns)
        put("moved_turns", movedTurns)
        put("priced_moved_turns", pricedMovedTurns)
    }

    /** The provider model entry's rates, keyed on the canonical id as SessionCost keys them. */
    private fun rates(model: String?, catalog: ModelCatalog?) = catalog?.live()?.let { c ->
        model?.let(c::stripSuffixes)?.let { key ->
            cost.ratesFor(null, key, c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates)
        }
    }
}
