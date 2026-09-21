// NEW: V4-160 — the team economics, moved verbatim out of TeamsRoutes.kt (concentration, 2026-09-18).
// TeamsRoutes.kt's header states what the economics count and why.
//
// V4-159: WHAT "A CHECK" IS FOR A MEMBER, and where it is read from. The daemon observes nothing
// inside a session's own tool calls (no git status, no test runner, no CI) — the one thing it DOES
// observe per slot, already tallied here for tokens and cost, is whether each of the slot's turns
// reached the backend and came back cleanly: PerfRow.outcome, the same tag TurnPipeline/TurnEnding
// write and PerfSummary reads (splice.core.perf.OutcomeTag, kt-outcome-tag-single-source). A "check"
// is therefore defined as the outcome tag of the slot's most recently tallied turn: "pass" when that
// tag is OutcomeTag.OK, "fail" for anything else (a cancelled, rate-limited, upstream-failed or
// otherwise non-OK turn) — a turn-health signal, not a build/test verdict. A slot that has tallied no
// turns at all has nothing to observe, so it stays the honest empty: checks null, checks_source
// naming why, exactly as it did before this row landed (CONTRACTS section 8).
package splice.control.api.sessions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.ManagedHead
import splice.control.PerfRow
import splice.core.model.ModelCatalog
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.teams.Team
import splice.core.teams.TeamSlot

/** Where a present [PerfTally.lastOutcome]-derived `checks` value came from. */
internal const val CHECKS_SOURCE = "the outcome tag of the slot's most recently tallied turn (PerfRow.outcome)"

/** Why `checks` is null: the slot has tallied no turns yet, so there is nothing to read. */
internal const val NO_TURNS_CHECKS_SOURCE = "no turns recorded yet for this slot"
private const val CHECKS_PASS = "pass"
private const val CHECKS_FAIL = "fail"

/** The single spelling a turn's outcome tag must match to count as a passing check
 *  (kt-outcome-tag-single-source: name the tag, never re-spell it). */
private val OK = OutcomeTag.OK.wire
private const val ROLE = "role"

/** The perf row's session tag width (TurnDrive.SESSION_TAG_CHARS). */
private const val PERF_TAG_CHARS = 8

/** Lifetime economics for one team over the perf files of every head its slots name. */
internal class TeamEconomics(private val team: Team, private val heads: Map<String, ManagedHead>) {
    private val slotOfTag = team.slots
        .flatMap { slot -> held(slot).map { it.take(PERF_TAG_CHARS) to slot } }
        .distinctBy { it.first }
        .toMap()
    private val bySlot = team.slots.associate { it.id to PerfTally() }
    private val byRole = team.slots.map { it.role }.distinct().associateWith { PerfTally() }
    private var unattributed = 0
    private var oldest: Long? = null

    fun json(): JsonObject {
        val read = team.slots.map { it.head }.distinct().filter { it in heads }
        read.forEach { tally(heads.getValue(it)) }
        return buildJsonObject {
            put(TEAM_ID, team.id)
            put("heads_read", buildJsonArray { read.forEach { add(JsonPrimitive(it)) } })
            put("unattributed_turns", unattributed)
            put("oldest_turn_epoch_millis", oldest)
            put("roles", buildJsonArray { byRole.forEach { (role, tally) -> add(tally.json { it.put(ROLE, role) }) } })
            put(
                "slots",
                buildJsonArray {
                    bySlot.forEach { (slot, tally) ->
                        add(
                            tally.json {
                                it.put("slot", slot)
                                it.put("checks", checksOf(tally.lastOutcome))
                                it.put("checks_source", checksSourceOf(tally.lastOutcome))
                            },
                        )
                    }
                },
            )
        }
    }

    /** "pass"/"fail" from the slot's newest tallied outcome tag, null before its first turn. */
    private fun checksOf(outcome: String?): String? = outcome?.let { if (it == OK) CHECKS_PASS else CHECKS_FAIL }

    /** Where [checksOf] came from, or why it has nothing to report yet. */
    private fun checksSourceOf(outcome: String?): String =
        if (outcome == null) NO_TURNS_CHECKS_SOURCE else CHECKS_SOURCE

    /** Every session [slot] ever held, the current one included. */
    private fun held(slot: TeamSlot): List<String> = slot.sessionsHistory + listOfNotNull(slot.session)

    private fun tally(head: ManagedHead) {
        val window = head.perfRows?.window(0L) ?: return
        oldest = listOfNotNull(oldest, window.oldestHeldTs ?: window.rows.minOfOrNull { it.ts }).minOrNull()
        for (row in window.rows) {
            if (row.session == null) unattributed += 1
            row.session?.let(slotOfTag::get)?.let { slot ->
                bySlot.getValue(slot.id).add(row, head.catalog)
                byRole.getValue(slot.role).add(row, head.catalog)
            }
        }
    }
}

/** How a tally's JSON names the aggregate it covers (a role, a slot, a project). */
internal fun interface TallyLabel {
    operator fun invoke(into: JsonObjectBuilder)
}

/** Turns, token buckets and dollars over perf rows, each row priced against its own head's catalog
 *  with the SessionCost arithmetic. Dollars are null while any counted turn had no rate card. */
internal class PerfTally(private val cost: TokenCost = TokenCost()) {
    var turns: Long = 0L
        private set
    private var unpriced = 0L
    private var input = 0L
    private var cacheRead = 0L
    private var cacheWrite = 0L
    private var output = 0L
    private var usd = 0.0

    /** The newest counted turn, or null before the first. */
    var lastAt: Long? = null
        private set

    /** [lastAt]'s own outcome tag (V4-159's `checks` source) — the row whose ts set [lastAt], not
     *  simply the last row added: rows arrive in file/append order, but this stays correct even if
     *  a caller ever hands them out of order. Null before the first counted turn. */
    var lastOutcome: String? = null
        private set

    /** The priced total, or null while any counted turn had no rate card. */
    val costUsd: Double? get() = usd.takeIf { unpriced == 0L }

    fun add(row: PerfRow, catalog: ModelCatalog?) {
        val cached = row.fields[PerfKeys.CACHED_TOKENS] ?: 0L
        val written = row.fields[PerfKeys.CACHE_WRITE_TOKENS] ?: 0L
        // IN_TOKENS is inclusive of both cache buckets (SessionCost.bucketsFor says why), floored at 0.
        val buckets = TokenBuckets(
            input = ((row.fields[PerfKeys.IN_TOKENS] ?: 0L) - cached - written).coerceAtLeast(0L),
            cacheRead = cached,
            cacheWrite = written,
            output = row.fields[PerfKeys.OUT_TOKENS] ?: 0L,
        )
        turns += 1
        input += buckets.input
        cacheRead += buckets.cacheRead
        cacheWrite += buckets.cacheWrite
        output += buckets.output
        val previousLastAt = lastAt
        if (previousLastAt == null || row.ts >= previousLastAt) lastOutcome = row.outcome
        lastAt = maxOf(previousLastAt ?: row.ts, row.ts)
        val rates = rates(row.model, catalog)
        if (rates == null) unpriced += 1 else usd += cost.of(buckets, rates)
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
        put("unpriced_turns", unpriced)
        put("last_turn_at_epoch_millis", lastAt)
    }

    /** The provider model entry's rates, keyed on the canonical id as SessionCost keys them. */
    private fun rates(model: String?, catalog: ModelCatalog?) = catalog?.let { c ->
        model?.let(c::stripSuffixes)?.let { key ->
            cost.ratesFor(null, key, c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates)
        }
    }
}
