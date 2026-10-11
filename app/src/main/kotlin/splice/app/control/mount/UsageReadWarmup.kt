// NEW: (ledger lines 495, 519) prepares each head's Usage reads at startup, one cold scan at a time.
package splice.app.control.mount

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import splice.core.util.Cancellables
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeads
import splice.usage.UsageReadPreparation
import splice.usage.economics.EconomicsRead
import java.util.concurrent.ConcurrentHashMap

// A cold reconciliation retains decoded facts; one scan at a time avoids the measured fleet contention.
private const val COLD_READS_AT_ONCE = 1

// why: without hourly history, prepare the same 24-hour window a request with no explicit cutoff defaults to.
private const val DEFAULT_REQUEST_WINDOW_MS = 86_400_000L

/** Application-owned preparation changes only when readers may answer, never their stored facts or arithmetic. */
internal class UsageReadWarmup(
    private val scope: CoroutineScope,
    private val heads: UsageHeads,
    private val io: CoroutineDispatcher,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : UsageReadPreparation {
    private val admission = Semaphore(COLD_READS_AT_ONCE)
    private val states = ConcurrentHashMap<String, State>()

    /** Called by ApplicationStarted, not by the first Usage route. */
    fun start() {
        heads.all().forEach { prepare(it, null) }
    }

    override fun economicsReady(head: UsageHead): Boolean = prepare(head, null)

    override fun requestsReady(head: UsageHead, sinceMs: Long): Boolean = prepare(head, sinceMs)

    private fun prepare(head: UsageHead, sinceMs: Long?): Boolean {
        val state = states.computeIfAbsent(head.key) { State() }
        return synchronized(state) {
            if (sinceMs != null) state.wanted = minOf(state.wanted ?: sinceMs, sinceMs)
            state.failure?.let { failure ->
                state.failure = null
                throw failure
            }
            val ready = state.readySince
            val satisfied = ready != null && (sinceMs == null || sinceMs >= ready)
            if (!satisfied && !state.loading) {
                state.loading = true
                scope.launch(io) {
                    try {
                        admission.withPermit { load(head, state) }
                    } finally {
                        synchronized(state) { state.loading = false }
                    }
                }
            }
            satisfied && !state.loading
        }
    }

    private suspend fun load(head: UsageHead, state: State) {
        val context = currentCoroutineContext()
        val loaded = Cancellables.runCatchingCancellable {
            context.ensureActive()
            val economics = head.sinks.economics?.read()
            context.ensureActive()
            val first = (economics as? EconomicsRead.Rows)?.rows?.minOfOrNull { it.hour }
                ?: (clock() - DEFAULT_REQUEST_WINDOW_MS)
            var since: Long
            do {
                context.ensureActive()
                since = synchronized(state) { minOf(first, state.wanted ?: first) }
                head.sinks.perfRows?.window(since)
                context.ensureActive()
            } while (synchronized(state) { state.wanted?.let { it < since } == true })
            since
        }
        synchronized(state) {
            loaded.fold(
                onSuccess = { since -> state.readySince = minOf(state.readySince ?: since, since) },
                onFailure = { failure -> state.failure = failure },
            )
        }
    }

    private class State {
        var wanted: Long? = null
        var readySince: Long? = null
        var loading = false
        var failure: Throwable? = null
    }
}
