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
import splice.usage.UsageBilling
import splice.usage.UsageHead
import java.time.Instant
import java.time.ZoneId

/** Full filtered-window facts, computed from the route's existing read, before its display-row limit. */
internal class TurnUsage(rows: List<PerfRow>, price: TurnPrice?, plans: AccountPlans, zone: ZoneId) {
    private val totals = Counters()
    private val models = linkedMapOf<String?, Counters>()
    private val accounts = linkedMapOf<String?, Counters>()
    private val days = linkedMapOf<String?, Counters>()
    private val sessions = linkedMapOf<String?, Counters>()
    private val lastModels = mutableMapOf<String, LastModel>()

    init {
        val gaps = TurnPriceGap(price, plans)
        rows.forEach { row ->
            val cost = gaps.usd(row)
            val gap = gaps.of(row)
            val day = Instant.ofEpochMilli(row.ts).atZone(zone).toLocalDate().toString()
            totals.add(row, cost, gap)
            models.getOrPut(row.facts.model, ::Counters).add(row, cost, gap)
            accounts.getOrPut(row.facts.account, ::Counters).add(row, cost, gap)
            days.getOrPut(day, ::Counters).add(row, cost, gap)
            sessions.getOrPut(row.transcript.sessionId, ::Counters).add(row, cost, gap)
            recordModel(row)
        }
    }

    fun json(): JsonObject = buildJsonObject {
        put("totals", totals.json())
        put("models", grouped(models))
        put("accounts", grouped(accounts))
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

    private fun grouped(groups: Map<String?, Counters>): JsonArray = buildJsonArray {
        groups.forEach { (key, counters) ->
            addJsonObject {
                put("key", key)
                counters.json().forEach { (name, value) -> put(name, value) }
            }
        }
    }

    private data class LastModel(val ts: Long, val model: String)

    private class Counters {
        private var requests = 0L
        private var input: Long? = null
        private var cached: Long? = null
        private var output: Long? = null
        private var cost: Double? = null
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
        fun add(row: PerfRow, usd: Double?, gap: PriceGap?) {
            if (row.fields[PerfKeys.LOCAL_STEP] == 1L) {
                localSteps++
                return
            }
            requests++
            addTokens(row)
            cutRounds += row.fields[PerfKeys.CUT_SOURCE_ROUNDS] ?: 0L
            if (usd != null) cost = (cost ?: 0.0) + usd
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

/** The plan each recorded account runs on, as the head reports it now. An account the head's pool names
 *  answers for itself, an API key with no plan included; any other row falls back to the head's own quota. */
internal class AccountPlans(head: UsageHead, billing: UsageBilling? = null) {
    val kind: UsageBilling? = billing ?: if (head.anthropicUpstream) UsageBilling.SUBSCRIPTION else null

    // Read only when a request lacks a price, so a fully priced window never touches the pool or the quota file.
    private val byLabel by lazy {
        head.sinks.accountPool?.view(null)?.accounts.orEmpty().associate { it.label to it.plan }
    }
    private val headPlan by lazy { head.usage.snapshot().quota?.plan }

    fun of(account: String?): String? = if (account != null && account in byLabel) byLabel[account] else headPlan
}
