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
import java.time.Instant
import java.time.ZoneId

/** Full filtered-window facts, computed from the route's existing read, before its display-row limit. */
internal class TurnUsage(rows: List<PerfRow>, price: TurnPrice?, zone: ZoneId) {
    private val totals = Counters()
    private val models = linkedMapOf<String?, Counters>()
    private val accounts = linkedMapOf<String?, Counters>()
    private val days = linkedMapOf<String?, Counters>()
    private val sessions = linkedMapOf<String?, Counters>()
    private val lastModels = mutableMapOf<String, LastModel>()

    init {
        rows.forEach { row ->
            val known = PerfKeys.IN_TOKENS in row.fields && PerfKeys.OUT_TOKENS in row.fields
            val cost = if (known) price?.usd(row.model, row.fields) else null
            val day = Instant.ofEpochMilli(row.ts).atZone(zone).toLocalDate().toString()
            totals.add(row, cost)
            models.getOrPut(row.model, ::Counters).add(row, cost)
            accounts.getOrPut(row.account, ::Counters).add(row, cost)
            days.getOrPut(day, ::Counters).add(row, cost)
            sessions.getOrPut(row.sessionId, ::Counters).add(row, cost)
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
        val id = row.sessionId
        val model = row.model
        if (id != null && model != null) {
            val last = lastModels[id]
            if (row.compact != true && row.ts >= (last?.ts ?: Long.MIN_VALUE)) {
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
        private var unpriced = 0L
        private var missingInput = 0L
        private var missingOutput = 0L
        private var missingCache = 0L

        fun add(row: PerfRow, usd: Double?) {
            requests++
            addTokens(row)
            if (usd == null) unpriced++ else cost = (cost ?: 0.0) + usd
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
            put("input_tokens", input)
            put("cached_tokens", cached)
            put("output_tokens", output)
            put("cost_usd", cost)
            put("unpriced_requests", unpriced)
            put("missing_input_requests", missingInput)
            put("missing_output_requests", missingOutput)
            put("missing_cache_requests", missingCache)
            put("cache_share", cacheShare())
        }
    }
}
