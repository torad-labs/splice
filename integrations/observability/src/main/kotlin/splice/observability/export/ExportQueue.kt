package splice.observability.export

import io.opentelemetry.sdk.trace.data.SpanData
import splice.core.util.PerfWorkBudget
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

internal data class ExportQueued(val event: ExportEvent?, val span: SpanData?, val lease: PerfWorkBudget.Lease)

internal class ExportQueue : AutoCloseable {
    private val queue = ArrayBlockingQueue<ExportQueued>(QUEUE_CAPACITY)
    private var closed = false
    val dropped = AtomicLong()
    val enqueued = AtomicLong()

    @Synchronized
    fun offer(event: ExportEvent?, span: SpanData?): Boolean {
        val lease = if (closed) null else PerfWorkBudget.acquire(EVENT_BYTES)
        if (lease == null) { dropped.incrementAndGet(); return false }
        if (!queue.offer(ExportQueued(event, span, lease))) {
            lease.close()
            dropped.incrementAndGet()
            return false
        }
        enqueued.incrementAndGet()
        return true
    }

    fun poll(): ExportQueued? = queue.poll()
    fun pending(): Int = queue.size

    @Synchronized
    fun stop() { closed = true }

    @Synchronized
    override fun close() {
        closed = true
        var entry = queue.poll()
        while (entry != null) {
            entry.lease.close()
            dropped.incrementAndGet()
            entry = queue.poll()
        }
    }
}

private const val QUEUE_CAPACITY = 256
private const val EVENT_BYTES = 8_192L
