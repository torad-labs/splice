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
) {
    private val failureLogged = AtomicBoolean(false)
    private val firstLogged = AtomicBoolean(false)

    fun start(): Job = scope.launch {
        while (isActive) {
            pollOnce()
            if (!ticker.awaitTick(intervalMs)) return@launch
        }
    }

    internal suspend fun pollOnce() {
        Cancellables.runCatchingCancellable { probe.probe() }
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
