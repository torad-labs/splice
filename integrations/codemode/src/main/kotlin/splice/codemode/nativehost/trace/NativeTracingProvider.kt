package splice.codemode.nativehost.trace

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler

/** The process exporter is borrowed. This runtime owns only its native tracer provider and processor. */
internal class NativeTracingProvider(exporter: SpanExporter?) : AutoCloseable {
    private val provider = exporter?.let {
        SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
            .addSpanProcessor(SimpleSpanProcessor.create(BorrowedNativeExporter(it))).build()
    }
    val tracer: Tracer = provider?.get("splice.codemode") ?: GlobalOpenTelemetry.getTracer("splice.codemode")

    override fun close() { provider?.close() }
}

private class BorrowedNativeExporter(private val exporter: SpanExporter) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode = exporter.export(spans)
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
