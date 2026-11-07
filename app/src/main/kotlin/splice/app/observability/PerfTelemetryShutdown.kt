package splice.app.observability

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splice.core.config.ConfigService
import splice.core.util.Cancellables
import splice.core.util.PerfWorkPhase
import java.io.IOException

/** Settles producers and owns the final checkpoint phase through cancellation-safe dependent-owner cleanup. */
internal class PerfTelemetryShutdown(
    private val io: CoroutineDispatcher,
    private val schedule: PerfTelemetrySchedule,
    private val config: ConfigService,
) {
    suspend fun close(stopDelivery: PerfCloseAction, closeOwners: PerfPhaseClose) {
        val caller = currentCoroutineContext()
        withContext(NonCancellable + io) {
            val phase = try {
                schedule.finish(config, stopDelivery)
            } catch (failure: IOException) {
                caller.ensureActive()
                throw failure
            }
            phase.use {
                Cancellables.withCleanup({ closeOwners.close(it) }) { caller.ensureActive() }
            }
        }
        caller.ensureActive()
    }
}

internal fun interface PerfPhaseClose {
    fun close(phase: PerfWorkPhase.Lease)
}
