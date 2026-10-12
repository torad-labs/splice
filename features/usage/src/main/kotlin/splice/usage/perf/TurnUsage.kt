// NEW: V4-444 — complete-window usage facts precede the request display limit.
package splice.usage.perf

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.model.TurnPrice
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
    price: TurnPrice?,
    plans: AccountPlans,
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
        val gaps = TurnPriceGap(price, plans)
        rows.forEach { row ->
            val cost = gaps.usd(row)
            val value = gaps.value(row).takeIf { gaps.onPlan(row) }
            val gap = gaps.of(row)
            val day = Instant.ofEpochMilli(row.ts).atZone(zone).toLocalDate().toString()
            totals.add(row, Priced(cost, value), gap)
            models.getOrPut(row.facts.model, ::Counters).add(row, Priced(cost, value), gap)
            val account = reading.key(row.facts.account)
            reading.label(row.facts.account)?.let { label -> account?.let { accountLabels[it] = label } }
            accounts.getOrPut(account, ::Counters).add(row, Priced(cost, value), gap)
            days.getOrPut(day, ::Counters).add(row, Priced(cost, value), gap)
            val sessionKey = if (row.fields[PerfKeys.COMPARE] == 1L) COMPARE_BUCKET else row.transcript.sessionId
            sessions.getOrPut(sessionKey, ::Counters).add(row, Priced(cost, value), gap)
            recordModel(row)
        }
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
                put("last_model_ts_epoch_ms", last?.ts)
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

    private class Counters {
        private var requests = 0L
        private var input: Long? = null
        private var cached: Long? = null
        private var output: Long? = null
        private var cost: Double? = null

        // The requests a plan covered priced at the API rates: a value, never a cost.
        private var planValue: Double? = null
        private var planValued = 0L
        private val gaps = PriceGap.entries.associateWith { 0L }.toMutableMap()
        private var missingInput = 0L
        private var missingOutput = 0L
        private var missingCache = 0L

        // Source rounds these requests cut while they streamed: billed upstream, their tokens never reported.
        private var cutRounds = 0L

        // The code-mode steps in this group: replies splice wrote itself, never requests.
        private var localSteps = 0L

        /** V4-444: a code-mode step is a reply splice wrote itself, with nothing sent to a provider, so it is
         *  COUNTED APART from the requests, as the summary already counts it (PerfSummary.local_steps). Counting it
         *  as a request made every window with steps report requests that never reached a provider, each one also
         *  missing its input and output because a step has no token counts, and each one unpriced for a reason no
         *  case covers. It takes no token count, no price, no reason and no cut round: it has none of those. */
        fun add(row: PerfRow, priced: Priced, gap: PriceGap?) {
            if (row.fields[PerfKeys.LOCAL_STEP] == 1L) {
                localSteps++
                return
            }
            requests++
            addTokens(row)
            cutRounds += row.fields[PerfKeys.CUT_SOURCE_ROUNDS] ?: 0L
            priced.usd?.let { cost = (cost ?: 0.0) + it }
            priced.planValue?.let { planValue = (planValue ?: 0.0) + it; planValued++ }
            if (gap != null) gaps[gap] = gaps.getValue(gap) + 1
        }

        private fun addTokens(row: PerfRow) {
            val incoming = row.fields[PerfKeys.IN_TOKENS]
            val cache = row.fields[PerfKeys.CACHED_TOKENS]
            val outgoing = row.fields[PerfKeys.OUT_TOKENS]
            if (incoming == null) missingInput++ else input = (input ?: 0) + incoming
            if (outgoing == null) missingOutput++ else output = (output ?: 0) + outgoing
            if (cache != null) cached = (cached ?: 0) + cache
            if (incoming != null && cache == null) missingCache++
        }

        private fun cacheShare(): Double? {
            val total = input?.takeIf { it > 0 }
            return if (total != null && missingCache == 0L) (cached ?: 0).toDouble() / total else null
        }

        fun json(): JsonObject = buildJsonObject {
            put("requests", requests)
            put("local_steps", localSteps)
            put("input_tokens", input)
            put("cached_tokens", cached)
            put("output_tokens", output)
            put("cost_usd", cost)
            put("plan_requests", gaps.getValue(PriceGap.PLAN))
            put("plan_value_usd", planValue)
            put("plan_valued_requests", planValued)
            // Unanswered failures remain requests, but do not qualify estimates as missing spend.
            put("unpriced_requests", gaps.values.sum() - gaps.getValue(PriceGap.UNANSWERED))
            put("unpriced_uncounted_requests", gaps.getValue(PriceGap.UNCOUNTED))
            put("unpriced_plan_requests", gaps.getValue(PriceGap.PLAN))
            put("unpriced_local_requests", gaps.getValue(PriceGap.LOCAL))
            put("unpriced_undeclared_requests", gaps.getValue(PriceGap.UNDECLARED))
            put("unanswered_requests", gaps.getValue(PriceGap.UNANSWERED))
            put("missing_input_requests", missingInput)
            put("missing_output_requests", missingOutput)
            put("missing_cache_requests", missingCache)
            put("cut_source_rounds", cutRounds)
            put("cache_share", cacheShare())
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
private const val COMPARE_BUCKET = "compare"
