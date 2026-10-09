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

import splice.core.model.TurnBill
import splice.core.model.TurnPrice
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.core.turn.FailureCause
import splice.usage.UsageBilling

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
    /** The dollars this row billed, or null when [of] has a reason instead. */
    fun usd(row: PerfRow): Double? = price?.takeIf { declares(row) && counted(row) }?.usd(row.facts.model, row.fields)

    /** Null exactly when [usd] has a figure. */
    fun of(row: PerfRow): PriceGap? = when {
        unanswered(row) -> PriceGap.UNANSWERED
        usd(row) != null -> null
        plans.kind == UsageBilling.LOCAL_RUNTIME -> PriceGap.LOCAL
        plans.kind == UsageBilling.SUBSCRIPTION -> PriceGap.PLAN
        declares(row) -> PriceGap.UNCOUNTED
        plans.of(row.facts.account) != null -> PriceGap.PLAN
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
