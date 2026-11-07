package splice.observability.export

import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns a bounded queue and a dedicated network lane. No endpoint/signals means no SDK exporter is constructed. */
public class PerfExports(
    config: PerfExportConfig,
    identity: PerfIdentity,
    source: PerfAggregateSource,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : PerfObserver, AutoCloseable {
    private val config = config.copy(headers = config.headers.toMap(), signals = config.signals.toSet())
    private val eventEnabled = config.enabled && config.signals.any { it == TRACES || it == LOGS }
    private val queue = ExportQueue()
    private val closed = AtomicBoolean()
    private val engine = ExportEngine(config, identity, source, queue, clock, closed)
    private val native = NativeExportSpans(queue, closed, config.enabled && TRACES in config.signals)
    private val observed = AtomicLong()
    private val worker = ExportWorker(config, engine, queue, closed)

    override fun record(observation: PerfObservation): Boolean {
        if (closed.get()) return false
        observed.incrementAndGet()
        if (!eventEnabled) return true
        val accepted = queue.offer(ExportEvents.of(observation), null)
        if (LOGS in config.signals) {
            if (accepted) {
                engine.logsStatus.enqueued.incrementAndGet(); engine.logsStatus.pending.incrementAndGet()
            } else {
                engine.logsStatus.dropped.incrementAndGet()
            }
        }
        if (!accepted && TRACES in config.signals) engine.tracesStatus.dropped.incrementAndGet()
        return accepted
    }

    /** Compatibility projection: release a successful returned String with PerfWorkBudget.release after use. */
    public fun prometheus(): String = if (config.prometheus) {
        engine.prometheus()
    } else {
        "# splice Prometheus export disabled\n"
    }

    /** Preferred scrape port: keep the owner through encoding/delivery and close it afterward. */
    public fun scrape(): PerfScrape = if (config.prometheus) engine.scrape() else ScrapeUnavailable.of("disabled")

    /** Native SDK spans enter exactly once and are projected before they reach this component's bounded queue. */
    public fun nativeSpans(): SpanExporter = native.exporter()

    /** Caller releases this top-level owner with PerfWorkBudget.release after consuming it and all nested data. */
    public fun status(): JsonObject {
        val lease = PerfWorkBudget.acquire(EXPORT_STATUS_RESERVE_BYTES) ?: return STATUS_BUSY
        var retained = false
        return try {
            val result = statusData()
            lease.retain(result, EXPORT_STATUS_RESERVE_BYTES)
            retained = true
            result
        } finally {
            if (!retained) lease.close()
        }
    }

    private fun statusData(): JsonObject = buildJsonObject {
        put("prometheus_enabled", config.prometheus)
        put("otlp_enabled", config.enabled)
        put("endpoint_configured", !config.endpoint.isNullOrEmpty())
        put("protocol", config.protocol)
        put("headers", "[redacted]")
        put("signals", JsonArray(config.signals.sorted().map(::JsonPrimitive)))
        put("sampling", "all accepted observations; bounded drops reported; local capture unchanged")
        put("observed", observed.get())
        put("queue_enqueued", queue.enqueued.get())
        put("queue_dropped", queue.dropped.get())
        put("queue_pending", queue.pending())
        put("worker_failures", worker.failures)
        put("worker_finalized", worker.finalized)
        put("native_accepted", native.accepted)
        put("native_skipped", native.skipped)
        put("native_queue_failed", native.failed)
        put("coverage", engine.coverage())
        put("prometheus", engine.scrapeStatus())
        put("metrics", engine.metricsStatus.json())
        put(TRACES, engine.tracesStatus.json())
        put(LOGS, engine.logsStatus.json())
        put("closed", closed.get())
    }

    public fun flush(): Boolean = worker.flush()

    /** Stop scheduled work, drain and sample once under a bounded deadline, then close the exporters. */
    public fun closeAndFlush(): Boolean = worker.closeAndFlush()

    /** Stop scheduled work and bound the final drain/sample/close by [timeoutMs]. */
    public fun closeAndFlushWithin(timeoutMs: Long): Boolean = worker.closeAndFlush(timeoutMs)

    override fun close() { worker.close() }
}

private const val TRACES = "traces"
private const val LOGS = "logs"

// Reserve 96 KiB for each independent exporter-status response until its caller releases the returned tree.
private const val EXPORT_STATUS_RESERVE_BYTES = 96L * 1_024
private val STATUS_BUSY = buildJsonObject {
    put("status", "busy")
    put("reason", "shared_work_budget")
}
