// NEW: V4-444 — complete-window usage facts precede the request display limit.
package splice.usage.perf

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.usage.AccountPlanLookup
import splice.usage.UsageBilling
import splice.usage.UsageHead
import java.time.Instant
import java.time.ZoneId

/** Full filtered-window facts, computed from the route's existing read, before its display-row limit. */
/** [reading] groups a login by its ref and names it by its label: no account group is keyed by an email or an id. */
internal class TurnUsage(
    rows: List<PerfRow>,
    /** The read's own decisions, so a row priced for the totals is the row the list shows, priced once. */
    gaps: TurnPriceGap,
    zone: ZoneId,
    reading: AccountReading = AccountReading(HeadAccountNames { null }),
) {
    private val totals = Counters()
    private val models = linkedMapOf<String?, Counters>()
    private val accounts = linkedMapOf<String?, Counters>()
    private val accountLabels = mutableMapOf<String, String>()
    private val days = linkedMapOf<String?, Counters>()
    private val sessions = linkedMapOf<String?, Counters>()
    private val lastModels = mutableMapOf<String, LastModel>()

    init {
        rows.forEach { row ->
            val cost = gaps.usd(row)
            val value = gaps.value(row).takeIf { gaps.onPlan(row) }
            // the row's numbers are read once and added to its five groups: each read is a lookup into the packed row,
            // and reading them per group was most of a week's fold (jfr on desk c, Oct 12)
            val counted = counted(row, Priced(cost, value), gaps.of(row))
            val day = Instant.ofEpochMilli(row.ts).atZone(zone).toLocalDate().toString()
            totals.add(counted)
            models.getOrPut(row.facts.model, ::Counters).add(counted)
            val account = reading.key(row.facts.account)
            reading.label(row.facts.account)?.let { label -> account?.let { accountLabels[it] = label } }
            accounts.getOrPut(account, ::Counters).add(counted)
            days.getOrPut(day, ::Counters).add(counted)
            val sessionKey = if (row.fields[PerfKeys.COMPARE] == 1L) COMPARE_BUCKET else row.transcript.sessionId
            sessions.getOrPut(sessionKey, ::Counters).add(counted)
            recordModel(row)
        }
    }

    private fun counted(row: PerfRow, priced: Priced, gap: PriceGap?): Counted {
        val fields = row.fields
        return Counted(
            localStep = fields[PerfKeys.LOCAL_STEP] == 1L,
            tokens = Tokens(
                fields[PerfKeys.IN_TOKENS],
                fields[PerfKeys.CACHED_TOKENS],
                fields[PerfKeys.OUT_TOKENS],
                fields[PerfKeys.CACHE_WRITE_TOKENS],
            ),
            priced = priced,
            gap = gap,
            ending = when {
                row.outcome == OutcomeTag.RATE_LIMITED.wire ||
                    row.outcome == OutcomeTag.RATE_LIMIT_RELAYED.wire -> Ending.LIMITED
                OutcomeTags.isFailed(row.outcome) -> Ending.FAILED
                else -> Ending.OTHER
            },
        )
    }

    fun json(): JsonObject = buildJsonObject {
        put("totals", totals.json())
        put("models", grouped(models))
        put("accounts", grouped(accounts, accountLabels))
        put("days", grouped(days))
        put("sessions", sessionGroups())
    }

    private fun sessionGroups(): JsonArray = buildJsonArray {
        sessions.forEach { (id, counters) ->
            addJsonObject {
                put("key", id)
                counters.json().forEach { (name, value) -> put(name, value) }
                val last = lastModels[id]
                put("last_model", last?.model)
            }
        }
    }

    private fun recordModel(row: PerfRow) {
        val id = row.transcript.sessionId
        val model = row.facts.model
        if (id != null && model != null) {
            val last = lastModels[id]
            if (row.facts.compact != true && row.ts >= (last?.ts ?: Long.MIN_VALUE)) {
                lastModels[id] = LastModel(row.ts, model)
            }
        }
    }

    private fun grouped(
        groups: Map<String?, Counters>,
        labels: Map<String, String> = emptyMap(),
    ): JsonArray = buildJsonArray {
        groups.forEach { (key, counters) ->
            addJsonObject {
                put("key", key)
                key?.let(labels::get)?.let { put("label", it) }
                counters.json().forEach { (name, value) -> put(name, value) }
            }
        }
    }

    private data class LastModel(val ts: Long, val model: String)

    /** One row's figures: what a key was billed, or what a plan's request would have cost at the API rates. */
    private data class Priced(val usd: Double?, val planValue: Double?)

    /** One row as every group counts it, its numbers read out of the row once. */
    private data class Counted(
        val localStep: Boolean,
        val tokens: Tokens,
        val priced: Priced,
        val gap: PriceGap?,
        val ending: Ending = Ending.OTHER,
    )

    /** How a request ended, as the Usage chart splits a slice: refused for rate, failed some other way, or neither. */
    private enum class Ending { LIMITED, FAILED, OTHER }

    /** The row's reported token totals; null where the row never carried one. */
    private data class Tokens(val incoming: Long?, val cache: Long?, val outgoing: Long?, val cacheWrite: Long?)

    private class Counters {
        private var requests = 0L
        private var input: Long? = null
        private var cached: Long? = null
        private var cacheWritten: Long? = null
        private var output: Long? = null
        private var cost: Double? = null

        // The requests a plan covered priced at the API rates: a value, never a cost.
        private var planValue: Double? = null
        private val gaps = PriceGap.entries.associateWith { 0L }.toMutableMap()
        private var missingInput = 0L
        private var missingOutput = 0L
        private var failedRequests = 0L
        private var limitedRequests = 0L

        /** V4-444: a code-mode step is a reply splice wrote itself, with nothing sent to a provider, so it is
         *  LEFT OUT of the requests. Counting it as a request made every window with steps report requests that
         *  never reached a provider, each one also missing its input and output because a step has no token counts,
         *  and each one unpriced for a reason no case covers. It takes no token count, no price and no reason: it
         *  has none of those. */
        fun add(row: Counted) {
            if (row.localStep) return
            requests++
            addTokens(row.tokens)
            row.priced.usd?.let { cost = (cost ?: 0.0) + it }
            row.priced.planValue?.let { planValue = (planValue ?: 0.0) + it }
            if (row.gap != null) gaps[row.gap] = gaps.getValue(row.gap) + 1
            if (row.ending == Ending.LIMITED) limitedRequests++
            if (row.ending != Ending.OTHER) failedRequests++
        }

        private fun addTokens(tokens: Tokens) {
            val (incoming, cache, outgoing) = tokens
            if (incoming == null) missingInput++ else input = (input ?: 0) + incoming
            if (outgoing == null) missingOutput++ else output = (output ?: 0) + outgoing
            if (cache != null) cached = (cached ?: 0) + cache
            tokens.cacheWrite?.let { cacheWritten = (cacheWritten ?: 0) + it }
        }

        fun json(): JsonObject = buildJsonObject {
            put("requests", requests)
            put("input_tokens", input)
            put("cached_tokens", cached)
            put("cache_write_tokens", cacheWritten)
            put("output_tokens", output)
            put("cost_usd", cost)
            put("plan_requests", gaps.getValue(PriceGap.PLAN))
            put("plan_value_usd", planValue)
            // Unanswered failures remain requests, but do not qualify estimates as missing spend.
            put("unpriced_requests", gaps.values.sum() - gaps.getValue(PriceGap.UNANSWERED))
            put("unpriced_uncounted_requests", gaps.getValue(PriceGap.UNCOUNTED))
            put("unpriced_plan_requests", gaps.getValue(PriceGap.PLAN))
            put("unpriced_local_requests", gaps.getValue(PriceGap.LOCAL))
            put("unpriced_undeclared_requests", gaps.getValue(PriceGap.UNDECLARED))
            put("unanswered_requests", gaps.getValue(PriceGap.UNANSWERED))
            // the slice's failed requests and the rate-limited ones among them, the Usage chart's outcome mix
            put("failed_requests", failedRequests)
            put("rate_limited_requests", limitedRequests)
            put("missing_input_requests", missingInput)
            put("missing_output_requests", missingOutput)
        }
    }
}

/** The plan each recorded account runs on, as the head reports it now (see [AccountPlanLookup]). */
internal class AccountPlans(head: UsageHead, billing: UsageBilling? = null) {
    private val lookup = AccountPlanLookup(
        billing ?: if (head.anthropicUpstream) UsageBilling.SUBSCRIPTION else null,
        head.sinks.accountPool,
    ) { head.usage.snapshot().quota?.plan }

    val kind: UsageBilling? get() = lookup.kind

    fun of(account: String?): String? = lookup.planOf(account)

    fun covers(account: String?): Boolean = lookup.covers(account)

    fun coversEveryAccount(): Boolean = lookup.coversEveryAccount()
}

// why: Compare models sends carry no session; Usage's by-project view reads its per-session buckets, so the sends fold
// into one bucket of their own that the console names as a group, instead of the null key it leaves out.
internal const val COMPARE_BUCKET = "compare"
