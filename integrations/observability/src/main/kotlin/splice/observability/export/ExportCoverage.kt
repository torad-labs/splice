package splice.observability.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfCoverage
import splice.core.util.PerfWorkBudget
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private data class ExportCoverageSample(
    val data: JsonObject,
    val at: Long,
    val until: Long,
    val lease: PerfWorkBudget.Lease,
)

internal class ExportCoverage : AutoCloseable {
    private val current = AtomicReference<ExportCoverageSample?>()
    private val dropped = AtomicLong()

    fun update(source: PerfCoverage, totals: ExportTotals, now: Long): Boolean {
        val lease = PerfWorkBudget.acquire(COVERAGE_BYTES) ?: run { dropped.incrementAndGet(); return false }
        val data = buildJsonObject {
            put("as_of_ms", totals.asOfMs)
            put("sampled_epoch_ms", now)
            put("missed_expired_ms", totals.missingMs)
            put("source_issues", ExportSourceCoverage.count(source.issues))
            put("source", ExportSourceCoverage.of(source))
            put("metric_dropped", totals.dropped)
            put("temporality", "cumulative_since_export_start")
        }
        current.getAndSet(ExportCoverageSample(data, now, source.requestedUntilMs, lease))?.lease?.close()
        return true
    }

    fun json(now: Long, traceDropped: Long, missingContext: Long): JsonObject {
        val sample = current.get()
        return buildJsonObject {
            sample?.data?.forEach { (key, value) -> put(key, value) }
            put("sampled", sample != null)
            sample?.let {
                put("sample_age_ms", (now - it.at).coerceAtLeast(0))
                put("source_age_ms", (now - it.until).coerceAtLeast(0))
            }
            put("coverage_dropped", dropped.get())
            put("trace_dropped", traceDropped)
            put("trace_missing_context", missingContext)
        }
    }

    override fun close() {
        current.getAndSet(null)?.lease?.close()
    }
}

private const val COVERAGE_BYTES = 64L * 1_024
