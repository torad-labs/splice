// NEW: V4-160 — the team economics, moved verbatim out of TeamsRoutes.kt (concentration, 2026-09-18).
// TeamsRoutes.kt's header states what the economics count and why.
//
// PLAN WINDOWS (Oct 13, 2026; web-console §4.13 "Team tracking ... plan window use", marlin's call via splice-lead).
// Beside the lifetime figures, each slot carries what its sessions moved on a plan account over the trailing 5 hours
// and 7 days, the two windows Usage reads a plan over, by command and account. These count from the perf session
// tag as the lifetime does, and from before the team was made too, since a window is the plan's and not the team's.
// The console puts the provider's own used percent for that account beside them; splice never divides one by the other.
package splice.sessions.http

import kotlinx.serialization.json.JsonArray
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

/** The two windows a plan is read over, as Usage names them. */
private enum class PlanWindow(val key: String, val millis: Long) {
    FIVE_HOURS("5h", 5L * 60L * 60L * 1000L),
    SEVEN_DAYS("7d", 7L * 24L * 60L * 60L * 1000L),
}

/** One slot's use of one plan account through one command, over one window. */
private data class PlanUseKey(val slot: String, val window: PlanWindow, val head: String, val account: String?)

/** Requests and the tokens they moved, every bucket summed as the lifetime tokens figure is. */
private class PlanUse {
    var turns: Long = 0L
        private set
    var tokens: Long = 0L
        private set

    fun add(moved: Long) {
        turns += 1
        tokens += moved
    }
}

/** Lifetime economics for one team over the perf files of every head its slots name, and each slot's plan windows
 *  ending at [now]. */
internal class TeamEconomics(
    private val team: Team,
    private val heads: Map<String, SessionHead>,
    private val now: Long = System.currentTimeMillis(),
) {
    /** Every member, and every one that left: what a member cost stays on the team after it leaves (spec §14). */
    private val members = team.slots + team.left
    private val heldBy = members
        .flatMap { slot -> held(slot).map { Held(it.take(PERF_TAG_CHARS), slot, joined(slot, it)) } }
        .distinctBy { it.tag }
        .associateBy { it.tag }
    private val bySlot = members.associate { it.id to PerfTally() }
    private val planUse = LinkedHashMap<PlanUseKey, PlanUse>()
    private var oldest: Long? = null

    /** A session a member held, by its perf tag, and when it joined that member: its turns count from then. */
    private data class Held(val tag: String, val slot: TeamSlot, val joined: Long)

    fun json(): JsonObject {
        val read = members.map { it.head }.distinct().filter { it in heads }
        read.forEach { tally(it, heads.getValue(it)) }
        return buildJsonObject {
            put("oldest_turn_epoch_millis", oldest)
            put(
                "slots",
                buildJsonArray {
                    bySlot.forEach { (slot, tally) ->
                        add(
                            tally.json {
                                it.put("slot", slot)
                                it.put("left_epoch_millis", team.left.firstOrNull { gone -> gone.id == slot }?.leftAt)
                                it.put("plan_windows", windowsOf(slot))
                            },
                        )
                    }
                },
            )
        }
    }

    /** Every session [slot] ever held, the current one included. */
    private fun held(slot: TeamSlot): List<String> = slot.sessionsHistory + listOfNotNull(slot.session)

    /** When [session] joined [slot]: "since joining" is each member's own join, so a session bound later does not bring
     *  its earlier requests (the President's sign-off of §14, Oct 13). One bound before joins were kept reads from the
     *  team's creation, as before. */
    private fun joined(slot: TeamSlot, session: String): Long = slot.joinedAt[session] ?: team.createdAt

    /** The team's lifetime starts at its creation: a turn before it is no team's turn, even one a
     *  later-bound session made. The cutoff is also what keeps this read bounded now that the perf
     *  history reaches back through the archive (PerfRowsFileSource skips generations older than it);
     *  a team made before teams recorded a creation time reads from 0, as it always did. The read reaches
     *  back the longest plan window too, which a lifetime turn never counts from. */
    private fun tally(key: String, head: SessionHead) {
        val from = minOf(team.createdAt, now - PlanWindow.SEVEN_DAYS.millis)
        val window = head.perfRows?.window(from) ?: return
        oldest = listOfNotNull(oldest, window.oldestHeldTs ?: window.rows.minOfOrNull { it.ts }).minOrNull()
        window.rows.asSequence()
            .filter { it.fields[PerfKeys.LOCAL_STEP] != 1L } // No upstream turn to attribute to this team.
            .forEach { row -> row.session?.let(heldBy::get)?.let { held -> count(key, head, held, row) } }
    }

    private fun count(key: String, head: SessionHead, held: Held, row: SessionPerfRow) {
        val slot = held.slot
        if (row.ts >= held.joined) bySlot.getValue(slot.id).add(row, head.catalog, head.plans)
        if (!head.plans(row.account)) return
        val moved = TurnBill.total(row.fields).let { it.input + it.cacheRead + it.cacheWrite + it.output }
        PlanWindow.entries.filter { row.ts >= now - it.millis }.forEach { window ->
            planUse.getOrPut(PlanUseKey(slot.id, window, key, row.account)) { PlanUse() }.add(moved)
        }
    }

    /** [slot]'s plan use, the 5-hour window first, as the console draws it. */
    private fun windowsOf(slot: String): JsonArray = buildJsonArray {
        planUse.filterKeys { it.slot == slot }.entries.sortedBy { it.key.window.ordinal }.forEach { (use, tally) ->
            add(
                buildJsonObject {
                    put("window", use.window.key)
                    put("head", use.head)
                    put("account", use.account)
                    put("turns", tally.turns)
                    put("tokens", tally.tokens)
                },
            )
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
