// NEW: server mode (docs/specs/server-mode.md, F6). The server's metrics as Prometheus text (exposition format 0.0.4),
// the way an admin's scrape of `GET /server/metrics` reads them: each family's HELP and TYPE, then its series; a
// histogram's buckets cumulative up to `le="+Inf"`, with its `_sum` and `_count`.
package splice.server.metrics

import splice.core.server.HistogramPoint
import splice.core.server.MetricFamily
import splice.core.server.MetricLabels
import splice.core.server.MetricSnapshot
import splice.core.topology.metering.DEFAULT_SERVER_METRICS_PREFIX

internal class PrometheusExposition(private val prefix: String) {
    fun render(snapshot: MetricSnapshot): String = buildString {
        for (family in snapshot.families) {
            val name = prefix + family.name.removePrefix(DEFAULT_SERVER_METRICS_PREFIX)
            append("# HELP ").append(name).append(' ').append(help(family.help)).append('\n')
            append("# TYPE ").append(name).append(' ').append(type(family)).append('\n')
            when (family) {
                is MetricFamily.Counter -> family.points.forEach { sample(this, name, it.labels, it.value) }
                is MetricFamily.Gauge -> family.points.forEach { sample(this, name, it.labels, it.value) }
                is MetricFamily.Histogram -> family.points.forEach { histogram(this, name, family.bounds, it) }
            }
        }
    }

    private fun type(family: MetricFamily): String = when (family) {
        is MetricFamily.Counter -> "counter"
        is MetricFamily.Gauge -> "gauge"
        is MetricFamily.Histogram -> "histogram"
    }

    private fun histogram(out: StringBuilder, name: String, bounds: List<Double>, point: HistogramPoint) {
        var below = 0L
        bounds.forEachIndexed { at, bound ->
            below += point.buckets[at]
            sample(out, "${name}_bucket", point.labels.plus(LE, number(bound)), below.toDouble())
        }
        sample(out, "${name}_bucket", point.labels.plus(LE, INF), point.count.toDouble())
        sample(out, "${name}_sum", point.labels, point.sum)
        sample(out, "${name}_count", point.labels, point.count.toDouble())
    }

    private fun sample(out: StringBuilder, name: String, labels: MetricLabels, value: Double) {
        out.append(name)
        if (labels.pairs.isNotEmpty()) {
            out.append('{')
            labels.pairs.forEachIndexed { at, (label, text) ->
                if (at > 0) out.append(',')
                out.append(label).append("=\"").append(labelValue(text)).append('"')
            }
            out.append('}')
        }
        out.append(' ').append(number(value)).append('\n')
    }

    /** [value] as the format spells a number: whole numbers without a fraction, infinity as `+Inf`. */
    private fun number(value: Double): String = when {
        value == Double.POSITIVE_INFINITY -> INF
        value == Double.NEGATIVE_INFINITY -> "-Inf"
        value.isNaN() -> "NaN"
        value == Math.rint(value) && kotlin.math.abs(value) < WHOLE_LIMIT -> value.toLong().toString()
        else -> value.toString()
    }

    private fun labelValue(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun help(text: String): String = text.replace("\\", "\\\\").replace("\n", "\\n")
}

/** The Prometheus text media type, as a scrape is answered with it. */
internal const val PROMETHEUS_TEXT: String = "text/plain; version=0.0.4; charset=utf-8"

private const val LE = "le"
private const val INF = "+Inf"

// Past this a double no longer holds every whole number, so it is written as a double.
private const val WHOLE_LIMIT = 9.007199254740992E15
