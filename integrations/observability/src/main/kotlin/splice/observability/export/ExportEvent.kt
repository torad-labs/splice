package splice.observability.export

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceId
import io.opentelemetry.api.trace.TraceState
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfObservation

internal data class ExportEvent(
    val kind: PerfEventKind,
    val atNanos: Long,
    val context: SpanContext,
    val parent: SpanContext,
    val attributes: Attributes,
    val outcome: String?,
    val native: Boolean,
)

/** Copy only allowlisted numeric data and fixed dimension families before entering the queue. */
internal object ExportEvents {
    fun of(event: PerfObservation): ExportEvent {
        val links = event.links
        val currentContext = context(links.traceId, links.spanId, links.traceFlags)
        val parent = context(links.traceId, links.parentSpanId, links.traceFlags)
        val attributes = ExportSchema.attributes(ExportSchema.scope(event.scope)).toBuilder()
        event.values.entries.take(MAX_VALUES).forEach { (key, value) ->
            val metric = ExportSchema.metrics[key]?.takeIf { event.kind.name in it.events }
            if (metric != null && numeric(value)) {
                attributes.put(
                    metric.name,
                    value * metric.factor,
                )
            }
        }
        return ExportEvent(
            event.kind,
            Math.multiplyExact(event.stamp.wallMs.coerceIn(0, MAX_EPOCH_MS), NANOS_PER_MS),
            currentContext,
            parent,
            attributes.build(),
            event.outcome?.let(ExportSchema::outcome),
            event.values["tool_native_span"] == 1.0,
        )
    }

    private fun numeric(value: Double): Boolean = value.isFinite() && value >= 0.0

    private fun context(trace: String?, span: String?, flags: String?): SpanContext {
        if (trace == null || span == null) return SpanContext.getInvalid()
        if (!TraceId.isValid(trace) || !SpanId.isValid(span)) return SpanContext.getInvalid()
        val sampled = flags?.toIntOrNull(HEX_RADIX)?.and(1) == 1
        return SpanContext.create(
            trace,
            span,
            if (sampled) TraceFlags.getSampled() else TraceFlags.getDefault(),
            TraceState.getDefault(),
        )
    }
}

private const val MAX_VALUES = 64
private const val HEX_RADIX = 16
internal const val NANOS_PER_MS = 1_000_000L
private const val MAX_EPOCH_MS = Long.MAX_VALUE / NANOS_PER_MS
