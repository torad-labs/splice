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
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker
import splice.usage.UsageHead
import splice.usage.UsageHeads
import splice.usage.UsageReadPreparation
import splice.usage.economics.EconomicsRead
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.ProjectedPerfRowsSource
import java.util.concurrent.ConcurrentHashMap

// A cold reconciliation retains decoded facts; one scan at a time avoids the measured fleet contention.
private const val COLD_READS_AT_ONCE = 1

// why: the hours an hour-old read left unsettled grow with every turn since, and a heap left idle is paged out, so the
// first open after hours paid 6 to 15 s (measured live, Oct 12); a read every few minutes keeps both small and hot.
private const val KEEP_WARM_MS = 300_000L

// why: a failed refresh costs only the next open its own read, which is what it would have done anyway.
private const val KEEP_WARM_BEST_EFFORT = "the next economics read does the work this refresh would have"

// why: without hourly history, prepare the same 24-hour window a request with no explicit cutoff defaults to.
private const val DEFAULT_REQUEST_WINDOW_MS = 86_400_000L

/** Application-owned preparation changes only when readers may answer, never their stored facts or arithmetic. */
internal class UsageReadWarmup(
    private val scope: CoroutineScope,
    private val heads: UsageHeads,
    private val io: CoroutineDispatcher,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val ticker: Ticker = ProcessTicker(),
) : UsageReadPreparation {
    private val admission = Semaphore(COLD_READS_AT_ONCE)
    private val states = ConcurrentHashMap<String, State>()

    /** Called by ApplicationStarted, not by the first Usage route. */
    fun start() {
        heads.all().forEach { prepare(it, null) }
        scope.launch(io) {
            while (ticker.awaitTick(KEEP_WARM_MS)) heads.all().forEach { keepWarm(it) }
        }
    }

    /** Reads a prepared head's hourly totals again, so the hours since the last read are held before anyone asks. */
    private suspend fun keepWarm(head: UsageHead) {
        val state = states[head.key] ?: return
        if (synchronized(state) { !state.economicsReady || state.loading }) return
        admission.withPermit {
            val read = Cancellables.runCatchingBestEffort { head.sinks.economics?.read() }
            Cancellables.discard(read, KEEP_WARM_BEST_EFFORT)
        }
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
            // Hourly totals need only their own read: a person who opens Accounts first is not made to wait for the
            // request window the Requests page needs, which is warmed after every head's totals.
            val satisfied = satisfied(state, sinceMs)
            if (!satisfied && !state.loading) launch(head, state)
            satisfied && (sinceMs == null || !state.loading)
        }
    }

    private fun satisfied(state: State, sinceMs: Long?): Boolean {
        val ready = state.readySince
        return if (sinceMs == null) state.economicsReady else ready != null && sinceMs >= ready
    }

    private fun launch(head: UsageHead, state: State) {
        state.loading = true
        scope.launch(io) {
            try {
                // two turns at the permit: every head's totals queue ahead of any head's request window
                val totals = admission.withPermit { loadTotals(head, state) }
                totals.onSuccess { read -> admission.withPermit { loadWindow(head, state, read) } }
            } finally {
                synchronized(state) { state.loading = false }
            }
        }
    }

    private suspend fun loadTotals(head: UsageHead, state: State): Result<EconomicsRead?> {
        val context = currentCoroutineContext()
        val economics = Cancellables.runCatchingCancellable {
            context.ensureActive()
            head.sinks.economics?.read()
        }
        synchronized(state) {
            economics.fold(
                onSuccess = { state.economicsReady = true },
                onFailure = { failure -> state.failure = failure },
            )
        }
        return economics
    }

    /** The request window, after every head's hourly totals: it starts at the oldest hour those totals hold. */
    private suspend fun loadWindow(head: UsageHead, state: State, economics: EconomicsRead?) {
        val context = currentCoroutineContext()
        val loaded = Cancellables.runCatchingCancellable {
            context.ensureActive()
            val first = (economics as? EconomicsRead.Rows)?.rows?.minOfOrNull { it.hour }
                ?: (clock() - DEFAULT_REQUEST_WINDOW_MS)
            var since: Long
            do {
                context.ensureActive()
                since = synchronized(state) { minOf(first, state.wanted ?: first) }
                head.sinks.perfRows?.let { warm(it, since) }
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

    /** Reads the window the way the turns route will: through its projection where the source keeps one, so the
     *  tier that route answers from is the one made ready, and the whole-row window only for sources with no other. */
    private fun warm(source: PerfRowsSource, sinceMs: Long) {
        if (source is ProjectedPerfRowsSource) {
            source.projected(sinceMs) { projection -> projection.window.rows.size }
        } else {
            source.window(sinceMs)
        }
    }

    private class State {
        var wanted: Long? = null
        var economicsReady = false
        var readySince: Long? = null
        var loading = false
        var failure: Throwable? = null
    }
}
