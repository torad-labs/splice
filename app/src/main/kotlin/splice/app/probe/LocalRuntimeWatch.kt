// NEW: V4-417. Whether each local head's runtime answers, kept by the daemon from a background probe
// so /health and the heads route can say it without ever waiting on one. V4-415 asked the runtimes
// only from `splice status`; the console and /health read a head as ready or OK while its runtime was
// silent (Marlin's walk of f7f1e9308), because the daemon held no answer to give.
//
// Mirrors TurnPathProbeLoop's tick-loop idiom. The probe itself is LocalRuntimeReach, the one the CLI
// asks with: it bounds each runtime's wait, so a tick is at most that long and a wedged runtime reads
// as silent instead of stalling the loop. A reader gets the last answer, or nothing.
//
// NOTHING IS CLAIMED WHAT WAS NOT MEASURED. Until the first tick lands, and once the loop has died,
// [notAnswering] is empty and [unread] names every local head, so the console reads them as checking,
// never as ready (Models showed a down runtime Ready before the first tick). A dead loop that froze its
// last answer would keep calling a recovered runtime silent (or a dead one fine), so a death drops the
// answer and the loop is started again one interval later.
package splice.app.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.app.cli.status.LocalRuntimeReach
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import java.util.concurrent.atomic.AtomicReference

/** What the watch does for each local runtime that answers: re-read the window it serves (#399). */
internal fun interface LocalWindowRefresh {
    fun refresh(key: String, head: HeadConfig, provider: ProviderConfig)
}

internal class LocalRuntimeWatch(
    private val topology: Topology,
    private val log: LogSink,
    private val windows: LocalWindowRefresh = LocalWindowRefresh { _, _, _ -> },
    private val reach: LocalRuntimeReach = LocalRuntimeReach(),
    private val intervalMs: Long = WATCH_INTERVAL_MS,
    // The blocking probe rides the injected dispatcher and the cadence the injected ticker, as in
    // TurnPathProbeLoop (HD-19); both default to the values the composition root would have chosen.
    private val dispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val ticker: Ticker = ProcessTicker(),
) {
    private val silent = AtomicReference<Map<String, String>?>(null)

    // The scope the loop runs in, kept so a loop that died is started again in it.
    @Volatile
    private var scope: CoroutineScope? = null

    // True from a death until the next tick lands, so a loop that keeps dying says so once, not every interval.
    @Volatile
    private var dead = false

    /** Head key to the endpoint (`:8099`) of each local head whose runtime did not answer at the last
     *  probe. Empty before the first probe lands and after the watch died: no claim either way. */
    fun notAnswering(): Map<String, String> = silent.get().orEmpty()

    /** The local heads with no reading: every one before the first probe lands and after the watch died, none
     *  otherwise. A reader shows these as checking, never as answering or not. */
    fun unread(): Set<String> {
        if (silent.get() != null) return emptySet()
        return topology.heads.filterValues { topology.providers[it.provider]?.isLocal == true }.keys
    }

    fun start(scope: CoroutineScope): Job {
        this.scope = scope
        return launchLoop(scope, waitFirst = false)
    }

    private fun launchLoop(scope: CoroutineScope, waitFirst: Boolean): Job {
        val launched = scope.launch(dispatcher) {
            if (waitFirst && !ticker.awaitTick(intervalMs)) return@launch
            while (isActive) {
                // Probed at once, unlike TurnPathProbeLoop: a runtime is not a port this daemon is
                // still binding, so t=0 is not a false alarm, and an operator who opens Fleet in the
                // first seconds would otherwise read every silent runtime as fine.
                tick()
                if (!ticker.awaitTick(intervalMs)) return@launch
            }
        }
        return supervise(launched)
    }

    /** A dead probe stops claiming: its last answer would go stale in either direction, so the held
     *  answer is dropped, the loss said once, and the loop started again one interval later in the scope
     *  [start] was given. Cancellation is an orderly shutdown and says nothing.
     *  Supervision, not a broad catch in the loop, owns the unknown-throwable class (the sanctioned
     *  seam, as in TurnPathProbeLoop.supervise). Internal so the test drives the shipped handler. */
    internal fun supervise(job: Job): Job {
        job.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) return@invokeOnCompletion
            silent.set(null)
            if (!dead) {
                log(
                    "[daemon] LOCAL RUNTIME WATCH DIED ($cause); local runtimes read as checking until it " +
                        "answers again, restarting every ${intervalMs / MS_PER_SECOND}s.\n",
                )
            }
            dead = true
            scope?.takeIf { it.isActive }?.let { launchLoop(it, waitFirst = true) }
        }
        return job
    }

    /** A runtime that answers is asked again what it serves, so a runtime that came up after the daemon, or
     *  loaded a model since boot, moves its head's window instead of keeping the boot answer (#399). */
    private fun refreshAnswering(silentNow: Map<String, String>) {
        topology.heads.forEach { (key, head) ->
            val provider = topology.providers[head.provider]?.takeIf { it.isLocal } ?: return@forEach
            if (key !in silentNow) windows.refresh(key, head, provider)
        }
    }

    /** One probe of every local runtime, then a line for each runtime that changed. Exposed for tests,
     *  which drive ticks directly instead of waiting on the loop. */
    internal fun tick() {
        val now = reach.notAnswering(topology)
        val before = silent.getAndSet(now).orEmpty()
        if (dead) log("[daemon] local runtime watch running again.\n")
        dead = false
        refreshAnswering(now)
        now.filterKeys { it !in before }.forEach { (head, endpoint) ->
            log("[$head] runtime not answering on $endpoint; the console reads the head as down.\n")
        }
        before.filterKeys { it !in now }.forEach { (head, endpoint) ->
            log("[$head] runtime answering again on $endpoint.\n")
        }
    }
}

// How often each local runtime is asked. A runtime that comes back reads as such within a tick, and a
// refused connection answers at once, so the cost is one short request per local runtime per tick.
private const val WATCH_INTERVAL_MS = 5_000L

private const val MS_PER_SECOND = 1_000L
