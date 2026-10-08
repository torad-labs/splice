// NEW: keeps a head's quota windows fresh from its provider's usage endpoint: one probe at boot,
// then one every few minutes for the daemon's life. Runs on the daemon's own probe scope so
// Daemon.stop() ends it with everything else. A failing endpoint is logged once, then silence
// until it recovers — the bars simply keep the last snapshot.
//
// A probe that cannot reach the endpoint retries after 10 s, doubling up to the interval; an HTTP
// refusal is an answer and waits the full interval. The daemon starts at boot before
// the network is up, so its first probe is refused; waiting the whole interval after that left the
// GPT head with no 7d bar for five minutes after every reboot (2026-10-01, 2026-10-03).
package splice.usage.quota

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.usage.QuotaSnapshot
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.util.concurrent.atomic.AtomicBoolean

/** Where a fresh snapshot goes: app binds it to the head's QuotaTracker (features/turns), so the poller
 *  records without reaching into another capability. */
public fun interface QuotaSnapshotSink {
    public fun record(snapshot: QuotaSnapshot)
}

public class QuotaPoller(
    private val scope: CoroutineScope,
    private val head: String,
    private val probe: QuotaProbe,
    private val sink: QuotaSnapshotSink,
    private val log: LogSink,
    private val cadence: QuotaCadence = QuotaCadence(),
    private val clocks: QuotaClocks = QuotaClocks(),
) {
    private val failureLogged = AtomicBoolean(false)
    private val firstLogged = AtomicBoolean(false)
    private val lifecycle = Any()

    @Volatile private var job: Job? = null

    @Volatile private var stopped = false
    private val restartTimes = ArrayDeque<Long>()
    private val admission = Mutex()

    @Volatile private var completedAttempts = 0L

    private var lastSnapshot: QuotaSnapshot? = null
    private var lastProbeAtMs: Long? = null
    private var lastAnswered = true
    private val floorMs = minOf(cadence.intervalMs, QUOTA_PROBE_FLOOR_MS)
    private var reuseMs = floorMs

    /** Refresh without a turn. Concurrent opens share the active read, and reuse never changes observation time. */
    public suspend fun probeNow(): QuotaSnapshot? {
        val observedAttempt = completedAttempts
        return admission.withLock {
            if (observedAttempt == completedAttempts && !insideFloor()) attempt()
            lastSnapshot
        }
    }

    private fun insideFloor(): Boolean =
        lastProbeAtMs?.let { clocks.elapsedSince(it) < reuseMs } == true

    public fun start(): Job {
        synchronized(lifecycle) {
            val existing = job
            if (existing != null) return existing
            stopped = false
            return launchSupervised()
        }
    }

    public fun stop() {
        synchronized(lifecycle) {
            stopped = true
            job?.cancel()
            job = null
        }
    }

    private fun launchSupervised(): Job {
        val launched = scope.launch {
            var failures = 0
            while (isActive) {
                failures = if (pollOnce()) 0 else failures + 1
                if (!cadence.awaitNext(failures)) return@launch
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
        // Every value in a quota log line goes through LogSafe (kt-log-escapes-caller-input): the failure
        // text and the vendor's plan name arrive from outside, and a newline in either would forge a line.
        val why = SafeFailureText.render(cause)
        if (n <= MAX_RESTARTS) {
            val restarts = "$n/$MAX_RESTARTS"
            log(
                "[${LogSafe.str(head)}][quota] loop died: ${LogSafe.str(why)}; " +
                    "restarting (${LogSafe.str(restarts)})\n",
            )
            synchronized(lifecycle) {
                if (!stopped && job === launched) launchSupervised()
            }
        } else {
            val budget = "$MAX_RESTARTS in ${RESTART_WINDOW_MS / MS_PER_MIN}m"
            log(
                "[${LogSafe.str(head)}][quota] loop died: ${LogSafe.str(why)}; restart budget exhausted " +
                    "(${LogSafe.str(budget)}); probe permanently down\n",
            )
        }
    }

    private fun recordRestart(): Int = synchronized(restartTimes) {
        val now = clocks.now()
        while (restartTimes.isNotEmpty() && now - restartTimes.first() > RESTART_WINDOW_MS) {
            restartTimes.removeFirst()
        }
        restartTimes.addLast(now)
        restartTimes.size
    }

    /** True when the endpoint answered, an HTTP refusal included, so only an unreachable endpoint is retried
     *  early: a 429 asks for less traffic and a 401 does not heal in seconds. */
    internal suspend fun pollOnce(): Boolean {
        val observedAttempt = completedAttempts
        return admission.withLock {
            // Unreachable endpoints retain boot retries. A tick waiting on an active attempt shares its
            // result, including failures; answers and HTTP refusals also share the page-open floor.
            when {
                observedAttempt != completedAttempts -> lastAnswered
                lastAnswered && insideFloor() -> lastAnswered
                else -> attempt()
            }
        }
    }

    private suspend fun attempt(): Boolean {
        val result = Cancellables.runCatchingBestEffort { probe.probe() }
            .onSuccess { snapshot -> snapshot?.let(::accept) }
            .onFailure { failure ->
                if (failureLogged.compareAndSet(false, true)) {
                    // A refusal carries only its status, safe to say; any other failure may quote a body (V4-296).
                    val refused = failure as? QuotaEndpointRefused
                    val why = refused?.let { "HTTP ${it.status}" } ?: SafeFailureText.render(failure)
                    log(
                        "[${LogSafe.str(head)}][quota] usage probe failed (${LogSafe.str(why)}); " +
                            "bars keep the last snapshot until it is 15 minutes old\n",
                    )
                }
            }
        // The floor starts when the read completes, so waiters share even a slow provider answer.
        lastProbeAtMs = clocks.mark()
        val refused = result.exceptionOrNull() is QuotaEndpointRefused
        lastAnswered = result.isSuccess || refused
        reuseMs = if (refused) maxOf(cadence.intervalMs, floorMs) else floorMs
        completedAttempts++
        return lastAnswered
    }

    private fun accept(snapshot: QuotaSnapshot) {
        sink.record(snapshot)
        lastSnapshot = snapshot
        failureLogged.set(false)
        if (firstLogged.compareAndSet(false, true)) {
            val five = snapshot.fiveHour?.let { "5h ${it.usedPercent.toInt()}%" } ?: "5h n/a"
            val seven = snapshot.sevenDay?.let { "7d ${it.usedPercent.toInt()}%" } ?: "7d n/a"
            val plan = snapshot.plan?.let { " (plan $it)" }.orEmpty()
            log("[${LogSafe.str(head)}][quota] ${LogSafe.str(five)}, ${LogSafe.str(seven)}${LogSafe.str(plan)}\n")
        }
    }
}

// why: navigation cannot issue more than one successful probe per minute; a configured shorter poll interval stays its floor.
private const val QUOTA_PROBE_FLOOR_MS = 60_000L

private const val MAX_RESTARTS = 5
private const val RESTART_WINDOW_MS = 600_000L
private const val MS_PER_MIN = 60_000L
