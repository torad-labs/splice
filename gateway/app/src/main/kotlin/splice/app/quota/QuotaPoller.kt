// NEW: keeps a head's quota windows fresh from its provider's usage endpoint: one probe at boot,
// then one every few minutes for the daemon's life. Runs on the daemon's own probe scope so
// Daemon.stop() ends it with everything else. A failing endpoint is logged once, then silence
// until it recovers — the bars simply keep the last snapshot.
package splice.app.quota

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.core.usage.QuotaSnapshot
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.gateway.usage.QuotaTracker
import splice.spi.ProcessTicker
import splice.spi.Ticker
import java.util.concurrent.atomic.AtomicBoolean

internal class QuotaPoller(
    private val scope: CoroutineScope,
    private val head: String,
    private val probe: QuotaProbe,
    private val tracker: QuotaTracker,
    private val log: LogSink,
    private val intervalMs: Long = QUOTA_POLL_INTERVAL_MS,
    private val ticker: Ticker = ProcessTicker(),
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val failureLogged = AtomicBoolean(false)
    private val firstLogged = AtomicBoolean(false)
    private val lifecycle = Any()

    @Volatile private var job: Job? = null

    @Volatile private var stopped = false
    private val restartTimes = ArrayDeque<Long>()

    fun start(): Job {
        synchronized(lifecycle) {
            val existing = job
            if (existing != null) return existing
            stopped = false
            return launchSupervised()
        }
    }

    fun stop() {
        synchronized(lifecycle) {
            stopped = true
            job?.cancel()
            job = null
        }
    }

    private fun launchSupervised(): Job {
        val launched = scope.launch {
            while (isActive) {
                pollOnce()
                if (!ticker.awaitTick(intervalMs)) return@launch
            }
        }
        job = launched
        launched.invokeOnCompletion { cause -> superviseCompletion(launched, cause) }
        return launched
    }

    private fun superviseCompletion(launched: Job, cause: Throwable?) {
        if (cause == null || cause is kotlinx.coroutines.CancellationException) return
        val n = synchronized(lifecycle) {
            if (stopped || job !== launched) return
            recordRestart()
        }
        val why = SafeFailureText.render(cause)
        if (n <= MAX_RESTARTS) {
            log("[$head][quota] loop died: $why — restarting ($n/$MAX_RESTARTS)\n")
            synchronized(lifecycle) {
                if (!stopped && job === launched) launchSupervised()
            }
        } else {
            log(
                "[$head][quota] loop died: $why — restart budget exhausted " +
                    "($MAX_RESTARTS in ${RESTART_WINDOW_MS / MS_PER_MIN}m); probe permanently down\n",
            )
        }
    }

    private fun recordRestart(): Int = synchronized(restartTimes) {
        val now = clock()
        while (restartTimes.isNotEmpty() && now - restartTimes.first() > RESTART_WINDOW_MS) {
            restartTimes.removeFirst()
        }
        restartTimes.addLast(now)
        restartTimes.size
    }

    internal suspend fun pollOnce() {
        Cancellables.runCatchingBestEffort { probe.probe() }
            .onSuccess { snapshot -> snapshot?.let(::accept) }
            .onFailure { failure ->
                if (failureLogged.compareAndSet(false, true)) {
                    val why = SafeFailureText.render(failure)
                    log("[$head][quota] usage probe failed ($why) — bars keep the last snapshot\n")
                }
            }
    }

    private fun accept(snapshot: QuotaSnapshot) {
        tracker.record(snapshot)
        failureLogged.set(false)
        if (firstLogged.compareAndSet(false, true)) {
            val five = snapshot.fiveHour?.let { "5h ${it.usedPercent.toInt()}%" } ?: "5h n/a"
            val seven = snapshot.sevenDay?.let { "7d ${it.usedPercent.toInt()}%" } ?: "7d n/a"
            log("[$head][quota] $five, $seven${snapshot.plan?.let { " (plan $it)" }.orEmpty()}\n")
        }
    }
}

internal const val QUOTA_POLL_INTERVAL_MS: Long = 5 * 60 * 1000L
private const val MAX_RESTARTS = 5
private const val RESTART_WINDOW_MS = 600_000L
private const val MS_PER_MIN = 60_000L
