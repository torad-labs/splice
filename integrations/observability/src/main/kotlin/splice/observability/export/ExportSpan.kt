package splice.observability.export

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.trace.data.StatusData

/** Exact existing trace/span identity, with no payload-bearing SDK attributes, event names or status descriptions. */
internal class ExportSpan(
    private val name: String,
    private val context: SpanContext,
    private val parent: SpanContext,
    private val start: Long,
    private val end: Long,
    private val attributes: Attributes,
    private val status: StatusData,
) : ExportSpanDefaults() {
    override fun getName(): String = name
    override fun getKind(): SpanKind = SpanKind.INTERNAL
    override fun getSpanContext(): SpanContext = context
    override fun getParentSpanContext(): SpanContext = parent
    override fun getStartEpochNanos(): Long = start
    override fun getEndEpochNanos(): Long = end
    override fun getAttributes(): Attributes = attributes
    override fun getStatus(): StatusData = status
    override fun getTotalAttributeCount(): Int = attributes.size()
}
