package splice.observability.export

import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.trace.data.SpanData
import splice.core.util.WallClock

/** Owns one bounded conversion and delivery batch, releasing every queued lease after its attempt. */
internal class ExportQueueDrainer(
    private val queue: ExportQueue,
    private val transport: OtlpTransport?,
    private val traces: ExportTraces,
    private val tracesStatus: ExportSignalStatus,
    private val logsStatus: ExportSignalStatus,
    private val clock: WallClock,
) {
    fun drain(): Boolean {
        val entries = ArrayList<ExportQueued>()
        val spans = ArrayList<SpanData>()
        val logs = ArrayList<LogRecordData>()
        try {
            repeat(MAX_BATCH) {
                val entry = queue.poll() ?: return@repeat
                entries += entry
                entry.span?.let(spans::add)
                entry.event?.let { event ->
                    if (transport?.traces != null) traces.accept(event)?.let(spans::add)
                    if (transport?.logs != null) logs += ExportLog(event)
                }
            }
            val now = clock()
            val spansOk = if (spans.isEmpty()) {
                true
            } else {
                tracesStatus.enqueued.addAndGet(spans.size.toLong())
                tracesStatus.pending.addAndGet(spans.size.toLong())
                tracesStatus.finish(requireNotNull(transport?.traces).export(spans), spans.size, now)
            }
            val logsOk = if (logs.isEmpty()) {
                true
            } else {
                logsStatus.finish(
                    requireNotNull(transport?.logs).export(logs),
                    logs.size,
                    now,
                )
            }
            return spansOk && logsOk
        } finally {
            entries.forEach { it.lease.close() }
        }
    }
}

// One drain handles at most the queue's 256-event capacity, leaving later arrivals for a subsequent tick.
private const val MAX_BATCH = 256
