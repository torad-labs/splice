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
import splice.core.util.Cancellables
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val engine = ExportEngine(config, identity, source, queue, clock)
    private val closed = AtomicBoolean()
    private val native = NativeExportSpans(queue, closed, config.enabled && TRACES in config.signals)
    private val flushing = AtomicBoolean()
    private val workerFailures = AtomicLong()
    private val observed = AtomicLong()
    private val worker = if (config.enabled || config.prometheus) {
        Executors.newSingleThreadScheduledExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply { name = "splice-telemetry-export"; isDaemon = true }
        }
    } else {
        null
    }
    private val schedule = worker?.scheduleWithFixedDelay({ tick() }, 0, SAMPLE_SECONDS, TimeUnit.SECONDS)

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
        put("worker_failures", workerFailures.get())
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

    public fun flush(): Boolean {
        val executor = worker ?: return !closed.get()
        if (closed.get() || !flushing.compareAndSet(false, true)) return false
        return try {
            Cancellables.runCatchingCancellable {
                executor.submit<Boolean> {
                    try { tick() } finally { flushing.set(false) }
                }.get(FLUSH_SECONDS, TimeUnit.SECONDS)
            }.getOrElse { workerFailures.incrementAndGet(); false }
        } finally {
            if (executor.isShutdown) flushing.set(false)
        }
    }

    private fun tick(): Boolean = Cancellables.runCatchingCancellable { engine.tick() }
        .getOrElse { workerFailures.incrementAndGet(); false }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        schedule?.cancel(false)
        queue.stop()
        val executor = worker
        if (executor == null) { engine.close(); return }
        val cleanup = executor.submit { tick(); engine.close() }
        Cancellables.runCatchingCancellable { cleanup.get(CLOSE_SECONDS, TimeUnit.SECONDS) }
            .onFailure { workerFailures.incrementAndGet() }
        executor.shutdownNow()
    }
}

// Refresh exports every five seconds on the dedicated worker, keeping snapshot conversion off request threads.
private const val SAMPLE_SECONDS = 5L

// Bound an explicit flush wait to ten seconds even if the collector or export worker stalls.
private const val FLUSH_SECONDS = 10L

// Give the final drain and SDK shutdown twenty seconds before interrupting the dedicated export worker.
private const val CLOSE_SECONDS = 20L

private const val TRACES = "traces"
private const val LOGS = "logs"

// Reserve 96 KiB for each independent exporter-status response until its caller releases the returned tree.
private const val EXPORT_STATUS_RESERVE_BYTES = 96L * 1_024
private val STATUS_BUSY = buildJsonObject {
    put("status", "busy")
    put("reason", "shared_work_budget")
}
