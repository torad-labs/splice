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

    @Volatile var dropped: Long = 0L
        private set

    @Volatile var missingContext: Long = 0L
        private set

    fun accept(event: ExportEvent): SpanData? = when {
        event.kind == PerfEventKind.CHECKPOINT -> { attach(event); null }
        event.kind in starts -> { start(event); null }
        else -> finish(event)
    }

    private fun start(event: ExportEvent) {
        val key = key(event) ?: return
        if (key !in active) {
            val lease = if (active.size < MAX_ACTIVE) PerfWorkBudget.acquire(SPAN_BYTES) else null
            if (lease == null) dropped++ else active[key] = ExportStarted(event, lease)
        }
    }

    private fun attach(event: ExportEvent) {
        if (!event.context.isValid) return
        val key = key(event) ?: return
        val started = active[key] ?: return
        if (!started.event.context.isValid) {
            active[key] = started.copy(event = event.copy(atNanos = started.event.atNanos))
        }
    }

    private fun key(event: ExportEvent): String? = when {
        event.kind in requestEvents && event.requestId != null -> "request:" + event.requestId
        event.context.isValid -> event.context.traceId + event.context.spanId
        else -> null
    }

    private fun finish(event: ExportEvent): SpanData? {
        val name = terminals[event.kind] ?: return null
        val started = remove(event) ?: return null
        val context = if (started.event.context.isValid) started.event else event
        return if (context.context.isValid) {
            span(name, started.event, event, context)
        } else {
            missingContext++; dropped++; null
        }
    }

    private fun remove(event: ExportEvent): ExportStarted? {
        val key = key(event) ?: run { missingContext++; dropped++; return null }
        val started = active.remove(key) ?: run { dropped++; return null }
        started.lease.close()
        return started
    }

    private fun span(name: String, start: ExportEvent, end: ExportEvent, context: ExportEvent): SpanData? {
        return if (start.native) {
            null
        } else {
            ExportSpan(
                name,
                context.context,
                context.parent,
                start.atNanos,
                maxOf(end.atNanos, start.atNanos),
                end.attributes,
                if (end.outcome == "good") StatusData.ok() else StatusData.error(),
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
    private val requestEvents =
        setOf(PerfEventKind.REQUEST_ARRIVED, PerfEventKind.CHECKPOINT, PerfEventKind.REQUEST_FINISHED)
    private val terminals = mapOf(
        PerfEventKind.REQUEST_FINISHED to "splice.request",
        PerfEventKind.ATTEMPT_FINISHED to "splice.attempt",
        PerfEventKind.TOOL_FINISHED to "splice.tool",
    )
}

private const val MAX_ACTIVE = 512
private const val SPAN_BYTES = 4_096L
