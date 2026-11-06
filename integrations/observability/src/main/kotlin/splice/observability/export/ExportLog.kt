package splice.observability.export

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.Value
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource

internal class ExportLog(private val event: ExportEvent) : LogRecordData {
    override fun getResource(): Resource = ExportResource.resource
    override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = ExportResource.scope
    override fun getTimestampEpochNanos(): Long = event.atNanos
    override fun getObservedTimestampEpochNanos(): Long = event.atNanos
    override fun getSpanContext(): SpanContext = event.context
    override fun getSeverity(): Severity = Severity.INFO
    override fun getSeverityText(): String = "INFO"
    override fun getBodyValue(): Value<*> = Value.of(event.kind.name)

    // SDK 1.67 still requires this legacy member; modern exporters read getBodyValue().
    @Deprecated("Use getBodyValue(); retained for the SDK LogRecordData compatibility contract")
    override fun getBody(): io.opentelemetry.sdk.logs.data.Body =
        io.opentelemetry.sdk.logs.data.Body.string(event.kind.name)
    override fun getEventName(): String = "splice." + event.kind.name.lowercase()
    override fun getAttributes(): Attributes = event.attributes
    override fun getTotalAttributeCount(): Int = event.attributes.size()
}
