// PORT-OF: ControlServer.kt (ControlPayloads.perfJson) @ a77531a — invariants unchanged: the
// per-head stage aggregation (NEW bottleneck instrument), split out as the sole importer of
// splice.core.perf in the file.
package splice.usage.perf

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.perf.PerfKeys
import splice.usage.UsageHeads

private const val KEY = "key"
private const val LABEL = "label"
private const val HEADS = "heads"

public class PerfPayloads(private val heads: UsageHeads) {

    private val summary = PerfSummary()

    /** `/api/perf/summary?window=1h|24h|7d` (v0.4.0, FEATURES.md §3): one summary per head. An absent
     *  window is 24h; an unknown one is refused with 400, never answered with a different window
     *  (the CLI refuses the same input). */
    public suspend fun summary(call: ApplicationCall) {
        val label = call.request.queryParameters["window"]
        val window = if (label == null) PerfWindow.H24 else summary.window(label)
        if (window == null) {
            call.respondText(
                buildJsonObject { put("error", "unknown window '$label'; use 1h, 24h or 7d") }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        } else {
            call.respondText(summaryJson(window), ContentType.Application.Json)
        }
    }

    internal fun summaryJson(window: PerfWindow): String = buildJsonObject {
        put("window", window.label)
        putJsonArray(HEADS) {
            heads.all().forEach { m ->
                addJsonObject {
                    put(KEY, m.key)
                    put(LABEL, m.label)
                    summary.summarize(m.perfRows, window).forEach { (k, v) -> put(k, v) }
                }
            }
        }
    }.toString()

    // {heads:[{key,label,count,stages:{<field>:{count,p50,p95,max}}}]} — fields are the TurnPerf
    // marks/counters (PerfKeys names), marks first in pipeline order, counters after.
    public fun perfJson(tailN: Int): String = buildJsonObject {
        put("window", tailN)
        putJsonArray(HEADS) {
            heads.all().forEach { m ->
                val rows = m.perf?.tailNumeric(tailN).orEmpty()
                addJsonObject {
                    put(KEY, m.key)
                    put(LABEL, m.label)
                    put("count", rows.size)
                    putJsonObject("stages") {
                        orderedPerfFields(rows).forEach { field ->
                            val values = rows.mapNotNull { it[field] }
                            summary.stats(values)?.let { put(field, it) }
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
}
