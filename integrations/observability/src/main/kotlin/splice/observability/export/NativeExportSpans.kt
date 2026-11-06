package splice.observability.export

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Native SDK adapters borrow queue admission; flushing and shutting down the service remain with PerfExports. */
internal class NativeExportSpans(
    private val queue: ExportQueue,
    private val closed: AtomicBoolean,
    private val enabled: Boolean,
) {
    private val acceptedCount = AtomicLong()
    private val skippedCount = AtomicLong()
    private val failedCount = AtomicLong()
    private val sanitizer = ExportTraces()
    val accepted: Long get() = acceptedCount.get()
    val skipped: Long get() = skippedCount.get()
    val failed: Long get() = failedCount.get()

    fun exporter(): SpanExporter = object : SpanExporter {
        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            if (closed.get() || !enabled) {
                skippedCount.addAndGet(spans.size.toLong())
                return CompletableResultCode.ofSuccess()
            }
            var accepted = true
            spans.forEach { span ->
                if (span.hasEnded() && span.spanContext.isValid) {
                    val offered = queue.offer(null, sanitizer.sanitize(span))
                    if (offered) acceptedCount.incrementAndGet() else failedCount.incrementAndGet()
                    accepted = offered && accepted
                } else {
                    skippedCount.incrementAndGet()
                }
            }
            return if (accepted) CompletableResultCode.ofSuccess() else CompletableResultCode.ofFailure()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }
}
