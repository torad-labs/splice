package splice.app.observability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.config.ConfigService
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.core.util.PerfWorkPhase
import splice.upstream.codemode.ProcessTicker
import splice.upstream.retry.InflightGate
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Owns the periodic and conflated configuration producers; stop must settle both before their sources can close. */
internal class PerfTelemetrySchedule(
    private val scope: CoroutineScope,
    private val tracking: PerfConfigTracking,
    private val recorder: PerfRecorder,
    private val log: LogSink,
    private val cleanup: PerfCleanup,
    private val work: PerfSamplingWork,
) {
    private val ticker = ProcessTicker()
    private val gates = ConcurrentHashMap<String, InflightGate>()
    private var sampler: Job? = null
    private val configTicks = Channel<Unit>(Channel.CONFLATED)
    private var configWorker: Job? = null
    val started: Boolean get() = sampler != null

    fun gate(head: String, gate: InflightGate) { gates[head] = gate }
    fun sampleGates() { gates.values.forEach(InflightGate::sampleObservations) }

    fun start() {
        tracking.afterChange = PerfConfigSignal { configTicks.trySend(Unit) }
        tracking.start()
        configWorker = scope.launch {
            for (ignored in configTicks) {
                runInterruptible {
                    cleanup.run("configuration_sample") { tracking.drain(); sampleGates() }
                }
            }
        }
        sampler = scope.launch { samplePeriodically() }
    }

    /** Stop producers first; final export shutdown runs before the final checkpoint phase is acquired. */
    suspend fun finish(config: ConfigService, stopDelivery: PerfCloseAction, timeoutMs: Long) {
        config.effectiveObserver = null
        if (!stop(stopDelivery, timeoutMs)) {
            cleanup.failed("producers_pending")
            throw IOException("performance producers or checkpoint phase did not settle")
        }
    }

    private suspend fun stop(stopDelivery: PerfCloseAction, timeoutMs: Long): Boolean {
        val jobs = listOfNotNull(sampler, configWorker)
        jobs.forEach { it.cancel() }
        val stopped = withTimeoutOrNull(timeoutMs) {
            jobs.forEach { it.join() }
            stopDelivery.close()
            checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            true
        } == true
        if (stopped) configTicks.close()
        return stopped
    }

    fun measured(metric: String, durationMs: Long, owner: String) {
        recorder.emit(
            PerfScope(work = "instrumentation", model = owner),
            PerfEventKind.SELF_COST,
            mapOf(metric to durationMs.toDouble()),
        )
    }

    private suspend fun samplePeriodically() {
        var due = MonoClock.nowMs()
        while (currentCoroutineContext().isActive) {
            val start = MonoClock.nowMs()
            measured("self_scheduler_delay_ms", (start - due).coerceAtLeast(0), "perf_sampler")
            Cancellables.runCatchingBestEffort {
                PerfWorkPhase.checkpoint().use { phase -> runInterruptible { work.sample(phase) } }
            }.onFailure {
                recorder.emit(
                    PerfScope(work = "instrumentation"),
                    PerfEventKind.SELF_COST,
                    mapOf("self_failures" to 1.0),
                )
                log("[perf] periodic observation or persistence failed\n")
            }
            val ended = MonoClock.nowMs()
            measured("self_sampler_ms", ended - start, "perf_sampler")
            due = if (ended - start < TELEMETRY_SAMPLE_MS) start + TELEMETRY_SAMPLE_MS else ended + TELEMETRY_SAMPLE_MS
            if (!ticker.awaitTick((due - MonoClock.nowMs()).coerceAtLeast(1L))) break
        }
    }
}

internal fun interface PerfSamplingWork {
    fun sample(phase: PerfWorkPhase.Lease)
}

// Sample every five seconds on the owner worker; delayed work schedules a later tick instead of catching up in a burst.
private const val TELEMETRY_SAMPLE_MS = 5_000L

// Share fifteen seconds across producer and delivery shutdown before releasing dependent owners.
internal const val PRODUCER_STOP_MS = 15_000L
