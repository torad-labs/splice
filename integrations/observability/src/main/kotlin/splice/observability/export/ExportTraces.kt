package splice.observability.export

import io.opentelemetry.api.common.AttributeType
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import splice.core.perf.observation.PerfEventKind
import splice.core.util.PerfWorkBudget

private data class ExportStarted(val event: ExportEvent, val lease: PerfWorkBudget.Lease)

internal class ExportTraces : AutoCloseable {
    private val active = LinkedHashMap<String, ExportStarted>()
    var dropped: Long = 0L
        private set

    fun accept(event: ExportEvent): SpanData? = when {
        !event.context.isValid -> null
        event.kind in starts -> { start(event); null }
        else -> finish(event)
    }

    private fun start(event: ExportEvent) {
        val key = event.context.traceId + event.context.spanId
        if (key !in active) {
            val lease = if (active.size < MAX_ACTIVE) PerfWorkBudget.acquire(SPAN_BYTES) else null
            if (lease == null) dropped++ else active[key] = ExportStarted(event, lease)
        }
    }

    private fun finish(event: ExportEvent): SpanData? {
        val key = event.context.traceId + event.context.spanId
        val name = terminals[event.kind] ?: return null
        val started = active.remove(key) ?: run { dropped++; return null }
        started.lease.close()
        return if (started.event.native) {
            null
        } else {
            ExportSpan(
                name,
                event.context,
                event.parent,
                started.event.atNanos,
                maxOf(event.atNanos, started.event.atNanos),
                event.attributes,
                if (event.outcome == "good") StatusData.ok() else StatusData.error(),
            )
        }
    }

    fun sanitize(span: SpanData): SpanData {
        val attributes = Attributes.builder()
        span.attributes.forEach { key, value ->
            val metric = ExportSchema.metrics[key.key.removePrefix("splice.")]
            if (metric != null && key.type in setOf(AttributeType.LONG, AttributeType.DOUBLE)) {
                val number = (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
                if (number != null) {
                    attributes.put(
                        metric.name,
                        number * metric.factor,
                    )
                }
            }
        }
        return ExportSpan(
            "splice.tool",
            cleanContext(span.spanContext),
            cleanContext(span.parentSpanContext),
            span.startEpochNanos,
            span.endEpochNanos,
            attributes.build(),
            StatusData.create(span.status.statusCode, ""),
        )
    }

    private fun cleanContext(value: SpanContext): SpanContext = if (value.isValid) {
        SpanContext.create(value.traceId, value.spanId, value.traceFlags, TraceState.getDefault())
    } else {
        SpanContext.getInvalid()
    }

    override fun close() {
        dropped += active.size
        active.values.forEach { it.lease.close() }
        active.clear()
    }

    private val starts = setOf(PerfEventKind.REQUEST_ARRIVED, PerfEventKind.ATTEMPT_STARTED, PerfEventKind.TOOL_STARTED)
    private val terminals = mapOf(
        PerfEventKind.REQUEST_FINISHED to "splice.request",
        PerfEventKind.ATTEMPT_FINISHED to "splice.attempt",
        PerfEventKind.TOOL_FINISHED to "splice.tool",
    )
}

private const val MAX_ACTIVE = 512
private const val SPAN_BYTES = 4_096L
