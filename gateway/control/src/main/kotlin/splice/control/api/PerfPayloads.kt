// PORT-OF: ControlServer.kt (ControlPayloads.perfJson) @ a77531a — invariants unchanged: the
// per-head stage aggregation (NEW bottleneck instrument), split out as the sole importer of
// splice.core.perf in the file.
package splice.control.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.control.ManagedHead
import splice.core.perf.PerfKeys

private const val KEY = "key"
private const val LABEL = "label"
private const val HEADS = "heads"
private const val P50 = 0.50
private const val P95 = 0.95

internal class PerfPayloads(private val heads: Map<String, ManagedHead>) {

    // {heads:[{key,label,count,stages:{<field>:{count,p50,p95,max}}}]} — fields are the TurnPerf
    // marks/counters (PerfKeys names), marks first in pipeline order, counters after.
    fun perfJson(tailN: Int): String = buildJsonObject {
        put("window", tailN)
        putJsonArray(HEADS) {
            heads.values.forEach { m ->
                val rows = m.perf?.tailNumeric(tailN).orEmpty()
                addJsonObject {
                    put(KEY, m.head.key)
                    put(LABEL, m.head.label)
                    put("count", rows.size)
                    putJsonObject("stages") {
                        orderedPerfFields(rows).forEach { field ->
                            val values = rows.mapNotNull { it[field] }
                            if (values.isNotEmpty()) put(field, statsJson(values))
                        }
                    }
                }
            }
        }
    }.toString()

    /** PerfKeys.markOrder first (pipeline order), then every other seen field alphabetically. */
    private fun orderedPerfFields(rows: List<Map<String, Long>>): List<String> {
        val seen = rows.flatMapTo(LinkedHashSet()) { it.keys } - "ts"
        val marks = PerfKeys.markOrder.filter { it in seen }
        val rest = (seen - PerfKeys.markOrder.toSet()).sorted()
        return marks + rest
    }

    private fun statsJson(values: List<Long>): JsonObject {
        val sorted = values.sorted()
        return buildJsonObject {
            put("count", sorted.size)
            put("p50", percentile(sorted, P50))
            put("p95", percentile(sorted, P95))
            put("max", sorted.last())
        }
    }

    /** Nearest-rank percentile on a pre-sorted list. */
    private fun percentile(sorted: List<Long>, q: Double): Long {
        val rank = kotlin.math.ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
