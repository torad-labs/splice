package splice.observability.export

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Owns final sampling, engine cleanup, and the terminal result of the exporter worker. */
internal class ExportFinalizer(
    private val engine: ExportEngine,
    private val workerFailures: AtomicLong,
) {
    private val outcome = CompletableFuture<Boolean>()
    private val samplingState = AtomicReference(SamplingState.PENDING)
    private val workerFinalized = AtomicBoolean()

    val finalized: Boolean get() = workerFinalized.get()
    val succeeded: Boolean get() = outcome.getNow(false)

    fun closeWithoutWorker(): Boolean {
        engine.close()
        workerFinalized.set(true)
        outcome.complete(true)
        return true
    }

    fun close(
        executor: ScheduledExecutorService,
        timeoutMs: Long,
        finalSample: FinalExportSample,
    ): Boolean {
        val sampling = executor.submit<Boolean> {
            if (!samplingState.compareAndSet(SamplingState.PENDING, SamplingState.RUNNING)) {
                false
            } else {
                try {
                    finalSample.sample(timeoutMs)
                } finally {
                    samplingState.set(SamplingState.FINISHED)
                }
            }
        }
        val cleanup = executor.submit<Boolean> {
            engine.close()
            workerFinalized.set(true)
            sampling.get()
        }
        return awaitFinalCleanup(executor, timeoutMs, sampling, cleanup)
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
        outcome.complete(result)
        return result
    }

    private fun awaitCleanup(cleanup: Future<Boolean>, timeoutMs: Long): Boolean? = try {
        cleanup.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    }

    private fun expireFinalSample(sampling: Future<Boolean>) {
        if (!samplingState.compareAndSet(SamplingState.PENDING, SamplingState.EXPIRED) &&
            samplingState.get() == SamplingState.RUNNING
        ) {
            sampling.cancel(true)
        }
    }

    private fun markFinalFailure() {
        workerFailures.incrementAndGet()
        outcome.complete(false)
    }

    private fun rethrowFatalOrCancellation(failure: Throwable?) {
        when (failure) {
            is CancellationException, is Error -> throw failure
            is ExecutionException -> rethrowFatalOrCancellation(failure.cause)
        }
    }

    private enum class SamplingState { PENDING, RUNNING, EXPIRED, FINISHED }
}

internal fun interface FinalExportSample {
    fun sample(timeoutMs: Long): Boolean
}
