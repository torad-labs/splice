package splice.codemode.nativehost.trace

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.util.concurrent.atomic.AtomicBoolean

/** The process exporter is borrowed. This runtime owns only its native tracer provider and processor. */
internal class NativeTracingProvider(exporter: SpanExporter?) : AutoCloseable {
    private val provider = exporter?.let {
        SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
            .addSpanProcessor(SimpleSpanProcessor.create(BorrowedNativeExporter(it))).build()
    }
    val tracer: Tracer = provider?.get("splice.codemode") ?: GlobalOpenTelemetry.getTracer("splice.codemode")
    private val ownership = Any()
    private var activeSpans = 0
    private var closing = false

    /** No registry or waiter: each existing span retains only its own provider ticket. */
    fun acquire(): NativeSpanOwnership? = synchronized(ownership) {
        if (closing) null else NativeSpanOwnership(this).also { activeSpans++ }
    }

    fun release() {
        val shutdown = synchronized(ownership) {
            check(activeSpans > 0) { "Native span ownership was already released" }
            activeSpans--
            closing && activeSpans == 0
        }
        if (shutdown) provider?.close()
    }

    /** Seals admission immediately; the existing operation finalizers release the last owned span without a wait. */
    override fun close() {
        val shutdown = synchronized(ownership) {
            if (closing) {
                false
            } else {
                closing = true
                activeSpans == 0
            }
        }
        if (shutdown) provider?.close()
    }
}

internal class NativeSpanOwnership(private val provider: NativeTracingProvider) : AutoCloseable {
    private val released = AtomicBoolean()

    override fun close() {
        if (released.compareAndSet(false, true)) provider.release()
    }
}

private class BorrowedNativeExporter(private val exporter: SpanExporter) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode = exporter.export(spans)
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
