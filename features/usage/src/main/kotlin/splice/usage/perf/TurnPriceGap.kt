// NEW: why a request has no dollar figure, decided ONCE for both readers of the same rows.
//
// /api/perf/turns answers with per-window counters (TurnUsage) and with each row (PerfRoutes.rowJson),
// and both say something about price: the counters say how many requests went unpriced and under which
// heading, the row says its own cost_usd. The decision behind both is the same one, and a second copy
// of it is a page whose totals and whose rows disagree, so the decision lives here and both of them call it.
//
// The names are the WIRE: the console renders the row's `cost_reason` by them, so they are stable
// lowercase words, and `null` is "this request has a price", never "we did not look".
package splice.usage.perf

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.model.TurnBill
import splice.core.model.TurnPrice
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.core.turn.FailureCause
import splice.usage.UsageBilling
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup

/** Why a request has no dollar figure. [wire] is the console's own word for it. */
internal enum class PriceGap(val wire: String) {
    UNCOUNTED("uncounted"),
    PLAN("plan"),
    LOCAL("local"),
    UNDECLARED("undeclared"),
    UNANSWERED("unanswered"),
}

private val UNREPORTED_SPEND_KEYS = listOf(
    PerfKeys.ABSORBED_ROUNDS,
    PerfKeys.CUT_SOURCE_ROUNDS,
    PerfKeys.UPSTREAM_REQ_BYTES,
)

/** A refused request's posted bytes bought nothing; a round it cut or absorbed was still billed. */
private val REFUSED_SPEND_KEYS = UNREPORTED_SPEND_KEYS - PerfKeys.UPSTREAM_REQ_BYTES

/** Whether the provider refused a request before sending any event: a cause that names a refusal of the
 *  request itself, and nothing received. 2026-10-04: seven such rows (rate limits and 4xx statuses, each
 *  posted, none with an event) read as possible spend on the 7-day usage page. The `when` is exhaustive so
 *  a new cause is decided here. A model refusal or a filtered generation is not one: the model answered. */
private object ProviderRefusal {
    fun before(row: PerfRow): Boolean =
        refusal(FailureCause.entries.firstOrNull { it.name == row.cause }) &&
            (row.fields[PerfKeys.EVENTS_IN] ?: 0L) == 0L && PerfKeys.FIRST_DELTA !in row.fields

    private fun refusal(cause: FailureCause?): Boolean = when (cause) {
        FailureCause.UPSTREAM_STATUS_4XX, FailureCause.VENDOR_RATE_LIMITED, FailureCause.VENDOR_QUOTA_EXHAUSTED,
        FailureCause.AUTH_MISSING, FailureCause.AUTH_REFRESH_FAILED, FailureCause.REQUEST_TOO_LARGE,
        -> true
        FailureCause.UPSTREAM_STALLED, FailureCause.UPSTREAM_TRUNCATED, FailureCause.UPSTREAM_CONN_RESET,
        FailureCause.UPSTREAM_STATUS_5XX, FailureCause.UPSTREAM_REPORTED, FailureCause.MODEL_REFUSED,
        FailureCause.CONTENT_FILTERED, FailureCause.TOOL_TEAR, FailureCause.DIALECT_UNSUPPORTED,
        FailureCause.CODE_MODE_PROTOCOL, FailureCause.INTERNAL, FailureCause.POOL_EXHAUSTED,
        FailureCause.ADMISSION_FULL, null,
        -> false
    }
}

/** One row's price and, when it has none, the reason. Built once per window from the head's own card and
 *  plans, so the window's counters and each row's own figure are the same decision read twice. */
internal class TurnPriceGap(private val price: TurnPrice?, private val plans: AccountPlans) {
    /** The dollars this row billed to a key, or null when [of] has a reason instead. A request on an account with a
     *  plan billed nothing: the plan covered it, so its API-rate figure is [value] and never a cost. */
    fun usd(row: PerfRow): Double? = if (onPlan(row)) null else value(row)

    /** What this row would cost at the API rates on the head's card, whoever paid for it; null with no card or
     *  without both reported token totals. */
    fun value(row: PerfRow): Double? =
        price?.takeIf { declares(row) && counted(row) }?.usd(row.facts.model, row.fields, row.ts)

    /** Whether a plan covered this request: its account has a plan (Oct 11, fin: "on your plan" is a fact about the
     *  account, never about the card having no price for the model). A local runtime has none, and a code-mode step
     *  or an unanswered failure sent nothing a plan could cover. */
    fun onPlan(row: PerfRow): Boolean =
        row.fields[PerfKeys.LOCAL_STEP] != 1L && !unanswered(row) && plans.covers(row.facts.account)

    /** Null when [usd] has a figure, and for a code-mode step, which has neither: a reply splice wrote itself
     *  asked no provider, so it has no price AND no reason for the absence. Before V4-444 a step fell through to
     *  "uncounted" on a command that prices its model, or to "plan" on a plan, which read on the Requests list as
     *  a request splice could not price rather than one it never sent. The totals count steps apart
     *  (TurnUsage.Counters.add), so no figure on the page loses its explanation. */
    fun of(row: PerfRow): PriceGap? = when {
        row.fields[PerfKeys.LOCAL_STEP] == 1L -> null
        unanswered(row) -> PriceGap.UNANSWERED
        onPlan(row) -> PriceGap.PLAN
        usd(row) != null -> null
        HeadPriceGap.of(plans.kind) != null -> HeadPriceGap.of(plans.kind)
        declares(row) -> PriceGap.UNCOUNTED
        else -> PriceGap.UNDECLARED
    }

    private fun declares(row: PerfRow): Boolean = price?.declares(row.facts.model) == true

    /** A cost needs both reported token totals; an early refusal without them is not a $0 turn. */
    private fun counted(row: PerfRow): Boolean =
        PerfKeys.IN_TOKENS in row.fields && PerfKeys.OUT_TOKENS in row.fields

    /** Failed with neither recorded model output nor billed usage; interrupted or absorbed source stays a gap.
     *  Posted bytes are possible spend unless the provider refused the request before any event. */
    private fun unanswered(row: PerfRow): Boolean =
        OutcomeTags.isFailed(row.outcome) && TurnBill.isEmpty(row.fields) &&
            (if (ProviderRefusal.before(row)) REFUSED_SPEND_KEYS else UNREPORTED_SPEND_KEYS)
                .none { row.fields.getOrDefault(it, 0L) > 0L } &&
            PerfKeys.FIRST_DELTA !in row.fields &&
            ((row.fields[PerfKeys.CONTENT_FRAMES_OUT] ?: 0L) == 0L || row.fields[PerfKeys.ATTEMPTS] == 0L)
}

/** Why a command's turns with no price have none, from the command alone: a local runtime's turns cost nothing
 *  to run, a plan's are covered by the plan, and on any other command a turn with no price is a model with no
 *  rate card. The same two head-wide branches [TurnPriceGap.of] takes first, so the console's Day row and the
 *  Requests page give one reason. An API-key command whose account carries a plan is the one case only a row
 *  can tell (Oct 10, 2026, Marlin: a plan turn reads "on your plan", never "with no price"). */
internal object HeadPriceGap {
    fun of(kind: UsageBilling?): PriceGap? = when (kind) {
        UsageBilling.LOCAL_RUNTIME -> PriceGap.LOCAL
        UsageBilling.SUBSCRIPTION -> PriceGap.PLAN
        UsageBilling.API_RATE, null -> null
    }

    /** The wire word for command [key]'s turns with no price, or null when [lookup] does not know the command. */
    fun wire(lookup: UsageHeadLookup?, key: String): String? {
        val heads = lookup ?: return null
        val head = heads.byName(key).firstOrNull { it.key == key } ?: return null
        return (of(AccountPlans(head, heads.billing(key)).kind) ?: PriceGap.UNDECLARED).wire
    }

    /** Whether a plan covers every account of [head], so none of its requests can be charged to a key. */
    fun coversEveryAccount(lookup: UsageHeadLookup?, head: UsageHead): Boolean =
        AccountPlans(head, lookup?.billing(head.key)).coversEveryAccount()
}

// What one reply costs, for a surface that sent a model a question itself (Compare models) and read the token totals
// off the answer: the decision a recorded request gets, asked of a row built from those totals, so a reply there and
// the same request on the Requests page can never read two different prices.

/** The reply the account on [head] was asked for: the model, who answered, and the tokens it reported. */
internal data class RepliedTokens(val model: String?, val account: String?, val tokensIn: Long, val tokensOut: Long)

internal class ReplyPrice(private val heads: UsageHeadLookup) {
    /** The row's own three price fields: `cost_usd` on a key, `plan_value_usd` on a plan, else `cost_reason`. */
    fun json(head: UsageHead, reply: RepliedTokens, at: Long): JsonObject {
        val gaps = TurnPriceGap(head.statusline.catalog?.let(::TurnPrice), AccountPlans(head, heads.billing(head.key)))
        val row = PerfRow(
            ts = at,
            outcome = OutcomeTag.OK.wire,
            fields = mapOf(
                PerfKeys.IN_TOKENS to reply.tokensIn,
                PerfKeys.CACHED_TOKENS to 0L,
                PerfKeys.CACHE_WRITE_TOKENS to 0L,
                PerfKeys.OUT_TOKENS to reply.tokensOut,
            ),
            facts = PerfTurnFacts(model = reply.model, account = reply.account),
        )
        return buildJsonObject {
            put("cost_usd", gaps.usd(row))
            put("cost_reason", gaps.of(row)?.wire)
            put("plan_value_usd", gaps.value(row).takeIf { gaps.onPlan(row) })
        }
    }
}
