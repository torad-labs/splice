// NEW: server mode (docs/specs/server-mode.md, F6). The server's per-person metrics sent to the operator's OTLP
// collector (`[telemetry.operator]`, with `metrics` among its signals): the same snapshot an admin's scrape reads,
// every five seconds on a lane of its own, cumulative since the daemon started, through the same endpoint, protocol,
// headers and bounded retries as the operator families. No endpoint, or no `metrics` signal, constructs nothing.
// A collector that is down costs one bounded attempt a push: nothing queues behind it, and the next push sends the
// totals again. The log says once when pushes start failing and once when they go through again.
package splice.observability.served

import splice.core.perf.export.PerfExportConfig
import splice.core.server.ServedMetricSource
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.observability.export.OtlpTransport
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

public class ServedMetricsExport(
    config: PerfExportConfig,
    private val source: ServedMetricSource,
    private val log: LogSink,
) : AutoCloseable {
    private val transport =
        if (config.enabled && METRICS in config.signals) OtlpTransport(config.copy(signals = setOf(METRICS))) else null
    private val exporter = transport?.metrics
    private val data = ServedMetricData()
    private val closed = AtomicBoolean()

    // Whether the last push failed, so the log says a run of failures once.
    private val failing = AtomicBoolean()

    private val worker = exporter?.let {
        Executors.newSingleThreadScheduledExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                name = "splice-server-metrics-export"
                isDaemon = true
            }
        }
    }
    private val schedule = worker?.scheduleWithFixedDelay(::push, PUSH_SECONDS, PUSH_SECONDS, TimeUnit.SECONDS)

    private fun push() {
        val exporter = exporter ?: return
        val sent = Cancellables.runCatchingBestEffort {
            val result = exporter.export(data.of(source.snapshot())).join(PUSH_WAIT_MS, TimeUnit.MILLISECONDS)
            check(result.isSuccess) { "the collector did not take the push" }
        }
        val failure = sent.exceptionOrNull()
        when {
            failure != null && !failing.getAndSet(true) ->
                log("[server] metrics did not reach the OTLP collector: ${SafeFailureText.render(failure)}\n")
            failure == null && failing.getAndSet(false) -> log("[server] metrics reach the OTLP collector again\n")
        }
    }

    /** Sends the totals once more, then stops: a bounded wait, so a collector that is down cannot hold a stop. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        schedule?.cancel(false)
        val worker = worker ?: return
        worker.submit(::push)
        worker.shutdown()
        if (!worker.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) worker.shutdownNow()
        transport?.shutdown()?.join(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
    }
}

private const val METRICS = "metrics"

// The operator families' own cadence (ExportWorker): a dashboard sees both move together.
private const val PUSH_SECONDS = 5L

// why: the transport bounds a push at two attempts of 750 ms with a 200 ms backoff at most; this is past that.
private const val PUSH_WAIT_MS = 2_500L

// why: the final push and the exporter's shutdown each wait this long at most, so a stop is never held long.
private const val CLOSE_WAIT_MS = 3_000L
