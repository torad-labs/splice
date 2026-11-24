package splice.server.ledger

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import splice.core.topology.ServerCostMeter
import splice.server.BookRun
import splice.upstream.LifecycleScope
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/** Probes only configured meters, independently of turns and report requests. */
internal class ExternalCostMeters(
    private val meters: Map<String, ServerCostMeter>,
    private val history: MeterIntervals,
    private val run: BookRun,
) {
    private val scope = LifecycleScope(run.io)
    private var signals: MeterSignals? = null

    fun open(configDir: Path) {
        if (meters.isEmpty()) return
        val signals = MeterSignals(configDir).also { this.signals = it }
        for ((name, meter) in meters) {
            scope.launch {
                val poll = MeterPoll(name, meter, history, run.clock)
                while (isActive) {
                    try {
                        poll.sample(signals.running(meter))
                    } catch (_: IOException) {
                        run.log("[server] cost meter '$name': could not persist an interval\n")
                    }
                    run.waiter.wait(poll.periodMs)
                }
            }
        }
    }

    fun costs(window: UsageWindow): List<FixedCost> = history.costs(meters.keys, window)

    suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        signals?.close()
        signals = null
    }
}

/** Only two adjacent positive observations establish an interval. No session carries an open interval into the next. */
private class MeterPoll(
    private val name: String,
    config: ServerCostMeter,
    private val history: MeterIntervals,
    private val clock: Clock,
) {
    val periodMs: Long = Duration.ofSeconds(config.pollSeconds.toLong()).toMillis()
    private val maxGapMs = periodMs + Duration.ofSeconds(METER_PROBE_SECONDS).toMillis()
    private val rate = checkNotNull(config.hourlyUsd)
    private var previous: Long? = null
    private var recordedThrough = history.recordedThrough(name)

    fun sample(running: Boolean) {
        val now = clock.millis()
        val before = previous
        previous = now.takeIf { running }
        if (!running || before == null) return
        if (now - before !in 1..maxGapMs) return
        val start = maxOf(before, recordedThrough ?: before)
        if (start < now) {
            history.add(MeterInterval(name, start, now, rate))
            recordedThrough = now
        }
    }
}
