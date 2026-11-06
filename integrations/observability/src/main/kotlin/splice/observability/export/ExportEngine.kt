package splice.observability.export

import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.serialization.json.JsonObject
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfIdentity
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicReference

internal class ExportEngine(
    private val config: PerfExportConfig,
    private val identity: PerfIdentity,
    private val source: PerfAggregateSource,
    private val queue: ExportQueue,
    private val clock: WallClock,
) : AutoCloseable {
    private val transport = if (config.enabled) OtlpTransport(config) else null
    private val totals = ExportTotals()
    private val traces = ExportTraces()
    private val text = AtomicReference("# splice operator telemetry has not sampled yet\n")
    private var textLease: PerfWorkBudget.Lease? = null
    private val coverage = ExportCoverage()
    val metricsStatus = ExportSignalStatus()
    val tracesStatus = ExportSignalStatus()
    val logsStatus = ExportSignalStatus()

    fun prometheus(): String = text.get()
    fun coverage(): JsonObject = coverage.json(clock(), traces.dropped, traces.missingContext)

    fun tick(): Boolean {
        var success = drain()
        if (config.prometheus || transport?.metrics != null) success = sample() && success
        return success
    }

    private fun drain(): Boolean {
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

    private fun sample(): Boolean {
        val lease = PerfWorkBudget.acquire(CONVERSION_BYTES)
        if (lease == null) { metricsStatus.dropped.incrementAndGet(); return false }
        return lease.use {
            val snapshot = source.snapshot()
            if (snapshot.identity != identity) { metricsStatus.failed.incrementAndGet(); return@use false }
            totals.reconcile(snapshot)
            val points = totals.points()
            if (config.prometheus) publish(PrometheusText.render(points, totals.dropped, totals.missingMs))
            val coverageOk = coverage.update(snapshot.coverage, totals, clock())
            if (!coverageOk) metricsStatus.dropped.incrementAndGet()
            val exporter = transport?.metrics ?: return@use coverageOk
            val data = OtlpMetricData.of(points)
            if (data.isEmpty()) return@use coverageOk
            metricsStatus.enqueued.addAndGet(data.size.toLong())
            metricsStatus.pending.addAndGet(data.size.toLong())
            metricsStatus.finish(exporter.export(data), data.size, clock()) && coverageOk
        }
    }

    private fun publish(value: String) {
        val lease = PerfWorkBudget.acquire(value.length * CHARACTER_BYTES)
        if (lease == null) { metricsStatus.dropped.incrementAndGet(); return }
        text.set(value)
        textLease?.close()
        textLease = lease
    }

    override fun close() {
        transport?.shutdown()?.join(EXPORT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        queue.close()
        logsStatus.dropped.addAndGet(logsStatus.pending.getAndSet(0))
        traces.close()
        totals.close()
        coverage.close()
        textLease?.close()
        textLease = null
        text.set("# splice operator telemetry closed\n")
    }
}

private const val CONVERSION_BYTES = 2L * 1_024 * 1_024
private const val CHARACTER_BYTES = 2L
private const val MAX_BATCH = 256
