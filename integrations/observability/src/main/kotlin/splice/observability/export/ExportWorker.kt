package splice.observability.export

import splice.core.perf.export.PerfExportConfig
import splice.core.util.Cancellables
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns the exporter lane's two periodic tasks, explicit flushes and final drain/shutdown. */
internal class ExportWorker(
    config: PerfExportConfig,
    private val engine: ExportEngine,
    private val queue: ExportQueue,
    private val closed: AtomicBoolean,
) : AutoCloseable {
    private val eventEnabled = config.enabled && config.signals.any { it == "traces" || it == "logs" }
    private val flushing = AtomicBoolean()
    private val workerFailures = AtomicLong()
    private val worker = if (config.enabled || config.prometheus) {
        Executors.newSingleThreadScheduledExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply { name = "splice-telemetry-export"; isDaemon = true }
        }
    } else {
        null
    }
    private val schedule = worker?.scheduleWithFixedDelay(
        { work(ExportWork.SAMPLE) },
        0,
        SAMPLE_SECONDS,
        TimeUnit.SECONDS,
    )
    private val events = if (eventEnabled) {
        worker?.scheduleWithFixedDelay(
            { work(ExportWork.DRAIN) },
            EVENT_BATCH_MS,
            EVENT_BATCH_MS,
            TimeUnit.MILLISECONDS,
        )
    } else {
        null
    }

    val failures: Long get() = workerFailures.get()

    fun flush(): Boolean {
        val executor = worker ?: return !closed.get()
        if (closed.get() || !flushing.compareAndSet(false, true)) return false
        return try {
            Cancellables.runCatchingCancellable {
                executor.submit<Boolean> {
                    try { work(ExportWork.TICK) } finally { flushing.set(false) }
                }.get(FLUSH_SECONDS, TimeUnit.SECONDS)
            }.getOrElse { workerFailures.incrementAndGet(); false }
        } finally {
            if (executor.isShutdown) flushing.set(false)
        }
    }

    private fun work(operation: ExportWork): Boolean = Cancellables.runCatchingBestEffort {
        when (operation) {
            ExportWork.SAMPLE -> engine.sample()
            ExportWork.DRAIN -> engine.drain()
            ExportWork.TICK -> engine.tick()
        }
    }.getOrElse {
        // Worker interruption is control flow, not a failed optional export sample.
        if (it is InterruptedException) throw it
        workerFailures.incrementAndGet()
        false
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        schedule?.cancel(false)
        events?.cancel(false)
        queue.stop()
        val executor = worker
        if (executor == null) { engine.close(); return }
        val cleanup = executor.submit { work(ExportWork.TICK); engine.close() }
        Cancellables.runCatchingCancellable { cleanup.get(CLOSE_SECONDS, TimeUnit.SECONDS) }
            .onFailure { workerFailures.incrementAndGet() }
        executor.shutdownNow()
    }
}

private enum class ExportWork { SAMPLE, DRAIN, TICK }

// Refresh exports every five seconds on the dedicated worker, keeping snapshot conversion off request threads.
private const val SAMPLE_SECONDS = 5L

// Coalesce event arrivals for at most 250 ms between bounded drains without scheduling a task per observation.
private const val EVENT_BATCH_MS = 250L

// Bound an explicit flush wait to ten seconds even if the collector or export worker stalls.
private const val FLUSH_SECONDS = 10L

// Give the final drain and SDK shutdown twenty seconds before interrupting the dedicated export worker.
private const val CLOSE_SECONDS = 20L
