package splice.observability.export

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource

internal object ExportResource {
    val resource: Resource = Resource.create(
        Attributes.of(io.opentelemetry.api.common.AttributeKey.stringKey("service.name"), "splice"),
    )
    val scope: InstrumentationScopeInfo = InstrumentationScopeInfo.builder("splice.operator").setVersion("1").build()
}
