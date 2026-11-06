package splice.observability.export

import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData

internal abstract class ExportSpanDefaults : SpanData {
    override fun getEvents(): List<EventData> = emptyList()
    override fun getLinks(): List<LinkData> = emptyList()
    override fun hasEnded(): Boolean = true
    override fun getTotalRecordedEvents(): Int = 0
    override fun getTotalRecordedLinks(): Int = 0

    // SDK 1.67 still requires this legacy member alongside getInstrumentationScopeInfo().
    @Deprecated("Use getInstrumentationScopeInfo(); retained for the SDK SpanData compatibility contract")
    override fun getInstrumentationLibraryInfo(): io.opentelemetry.sdk.common.InstrumentationLibraryInfo =
        io.opentelemetry.sdk.common.InstrumentationLibraryInfo.create("splice.operator", "1")
    override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = ExportResource.scope
    override fun getResource(): Resource = ExportResource.resource
}
