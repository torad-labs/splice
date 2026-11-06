package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonObject
import splice.core.perf.observation.PerfMetricRegistry

/** Only registry-defined measurements can become rules; cumulative runtime/gate samples remain gauges. */
internal object AlertMetricCatalog {
    private val definitions = ObjectiveJson.obj(PerfMetricRegistry.schema(), "metrics")
    private val aliases = setOf(
        "goodput.good_fraction",
        "goodput.per_s",
        "failure_fraction",
        "refusal_fraction",
        "retry_amplification",
        "telemetry.staleness_ms",
        "telemetry.loss_fraction",
    )

    fun supported(metric: String): Boolean {
        if (metric in aliases) return true
        val parts = metric.split('.')
        return if (parts.size == PARTS) registered(parts) else false
    }

    private fun registered(parts: List<String>): Boolean {
        val definition = definitions[parts[1]] as? JsonObject ?: return false
        return when (parts[0]) {
            "histograms" -> ObjectiveJson.text(
                definition,
                "type",
            ) == "histogram" && parts[2] in setOf("p95", "p99", "max")
            "gauges" -> ObjectiveJson.text(
                definition,
                "type",
            ) == "gauge" && parts[2] in setOf("last", "time_weighted_mean", "max")
            "counters" -> ObjectiveJson.text(definition, "type") == "counter" && parts[2] == "per_s"
            else -> false
        }
    }
}

// Registered alert metrics use three components: the metric prefix, registry identifier and selected statistic.
private const val PARTS = 3
