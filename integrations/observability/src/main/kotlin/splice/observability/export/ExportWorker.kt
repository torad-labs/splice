package splice.observability.export

import splice.core.perf.export.PerfExportConfig
import splice.core.util.Cancellables
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    private val finalOutcome = AtomicReference<Boolean?>(null)
    private val finalSampling = AtomicReference(FinalSampling.PENDING)
    private val workerFinalized = AtomicBoolean()
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
    val finalized: Boolean get() = workerFinalized.get()

    fun flush(): Boolean {
        val executor = worker ?: return !closed.get()
        if (closed.get() || !flushing.compareAndSet(false, true)) return false
        return try {
            Cancellables.runCatchingCancellable {
                executor.submit<Boolean> {
                    try { work(ExportWork.TICK, FLUSH_SECONDS) } finally { flushing.set(false) }
                }.get(FLUSH_SECONDS, TimeUnit.SECONDS)
            }.getOrElse { workerFailures.incrementAndGet(); false }
        } finally {
            if (executor.isShutdown) flushing.set(false)
        }
    }

    private fun work(operation: ExportWork, waitMs: Long = 0L): Boolean = Cancellables.runCatchingBestEffort {
        when (operation) {
            ExportWork.SAMPLE -> engine.sample()
            ExportWork.DRAIN -> engine.drain()
            ExportWork.TICK -> engine.tick(waitMs)
            ExportWork.FINAL_TICK -> engine.tick(waitMs, finalTick = true)
        }
    }.getOrElse {
        // Worker interruption is control flow, not a failed optional export sample.
        if (it is CancellationException) throw it
        if (it is InterruptedException) {
            Thread.currentThread().interrupt()
            throw it
        }
        workerFailures.incrementAndGet()
        false
    }

    fun closeAndFlush(): Boolean = closeAndFlush(CLOSE_MS)

    internal fun closeAndFlush(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0L)
        val boundedMs = minOf(timeoutMs, CLOSE_MS)
        if (!closed.compareAndSet(false, true)) return finalOutcome.get() ?: false
        schedule?.cancel(false)
        events?.cancel(false)
        queue.stop()
        val executor = worker
        if (executor == null) {
            engine.close()
            workerFinalized.set(true)
            finalOutcome.set(true)
            return true
        }
        return closeWorker(executor, boundedMs)
    }

    private fun closeWorker(executor: ScheduledExecutorService, timeoutMs: Long): Boolean {
        val sampling = submitFinalSample(executor, timeoutMs)
        val cleanup = submitEngineCleanup(executor, sampling)
        return awaitFinalCleanup(executor, timeoutMs, sampling, cleanup)
    }

    private fun submitFinalSample(executor: ScheduledExecutorService, timeoutMs: Long): Future<Boolean> =
        executor.submit<Boolean> {
            if (!finalSampling.compareAndSet(FinalSampling.PENDING, FinalSampling.RUNNING)) {
                false
            } else {
                try {
                    work(ExportWork.FINAL_TICK, timeoutMs)
                } finally {
                    finalSampling.set(FinalSampling.FINISHED)
                }
            }
        }

    private fun submitEngineCleanup(
        executor: ScheduledExecutorService,
        sampling: Future<Boolean>,
    ): Future<Boolean> = executor.submit<Boolean> {
        engine.close()
        workerFinalized.set(true)
        sampling.get()
    }

    private fun awaitFinalCleanup(
        executor: ScheduledExecutorService,
        timeoutMs: Long,
        sampling: Future<Boolean>,
        cleanup: Future<Boolean>,
    ): Boolean {
        var preserveCleanup = false
        val result = try {
            awaitCleanup(cleanup, timeoutMs) ?: run {
                preserveCleanup = true
                expireFinalSample(sampling)
                markFinalFailure()
                false
            }
        } catch (failure: ExecutionException) {
            markFinalFailure()
            rethrowFatalOrCancellation(failure.cause)
            false
        } catch (failure: InterruptedException) {
            preserveCleanup = true
            expireFinalSample(sampling)
            markFinalFailure()
            Thread.currentThread().interrupt()
            false
        } finally {
            if (preserveCleanup) executor.shutdown() else executor.shutdownNow()
        }
        finalOutcome.compareAndSet(null, result)
        return result
    }

    private fun awaitCleanup(cleanup: Future<Boolean>, timeoutMs: Long): Boolean? = try {
        cleanup.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    }

    private fun expireFinalSample(sampling: Future<Boolean>) {
        if (!finalSampling.compareAndSet(FinalSampling.PENDING, FinalSampling.EXPIRED) &&
            finalSampling.get() == FinalSampling.RUNNING
        ) {
            sampling.cancel(true)
        }
    }

    private fun markFinalFailure() {
        workerFailures.incrementAndGet()
        finalOutcome.compareAndSet(null, false)
    }

    private fun rethrowFatalOrCancellation(failure: Throwable?) {
        when (failure) {
            is CancellationException, is Error -> throw failure
            is ExecutionException -> rethrowFatalOrCancellation(failure.cause)
        }
    }

    override fun close() { closeAndFlush() }

    private enum class FinalSampling { PENDING, RUNNING, EXPIRED, FINISHED }
}

private enum class ExportWork { SAMPLE, DRAIN, TICK, FINAL_TICK }

// Refresh exports every five seconds on the dedicated worker, keeping snapshot conversion off request threads.
private const val SAMPLE_SECONDS = 5L

// Coalesce event arrivals for at most 250 ms between bounded drains without scheduling a task per observation.
private const val EVENT_BATCH_MS = 250L

// Bound an explicit flush wait to ten seconds even if the collector or export worker stalls.
private const val FLUSH_SECONDS = 10L

// Give the final drain and SDK shutdown twenty seconds before interrupting the dedicated export worker.
private const val CLOSE_MS = 20_000L
