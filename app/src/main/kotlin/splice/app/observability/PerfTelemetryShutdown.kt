package splice.app.observability

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.config.ConfigService
import splice.core.util.Cancellables
import splice.core.util.PerfWorkPhase
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Settles producers and owns the final checkpoint phase through cancellation-safe dependent-owner cleanup. */
internal class PerfTelemetryShutdown(
    private val io: CoroutineDispatcher,
    private val schedule: PerfTelemetrySchedule,
    private val config: ConfigService,
) {
    suspend fun close(
        stopDelivery: PerfCloseAction,
        stopExport: PerfExportStop,
        closeOwners: PerfPhaseClose,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_TOTAL_MS)
        val caller = currentCoroutineContext()
        withContext(NonCancellable + io) {
            try {
                schedule.finish(config, stopDelivery, min(PRODUCER_STOP_MS, remainingMs(deadline)))
            } catch (failure: IOException) {
                caller.ensureActive()
                throw failure
            }
            stopExport(min(EXPORT_CLOSE_MS, remainingMs(deadline)))
            val phase = withTimeoutOrNull(min(PRODUCER_STOP_MS, remainingMs(deadline))) {
                PerfWorkPhase.checkpoint()
            } ?: run {
                caller.ensureActive()
                throw IOException("performance checkpoint phase did not settle before owner cleanup")
            }
            phase.use {
                Cancellables.withCleanup({ closeOwners.close(it) }) { caller.ensureActive() }
            }
        }
        caller.ensureActive()
    }

    private fun remainingMs(deadline: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0L))
}

internal fun interface PerfExportStop {
    fun close(timeoutMs: Long): Boolean
}

internal fun interface PerfPhaseClose {
    fun close(phase: PerfWorkPhase.Lease)
}

private const val EXPORT_CLOSE_MS = 20_000L
private const val SHUTDOWN_TOTAL_MS = PRODUCER_STOP_MS + EXPORT_CLOSE_MS
