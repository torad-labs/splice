package splice.app.mods

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.mods.handover.ModHandoverStarts
import splice.app.mods.sessions.ModLaunches
import splice.app.mods.sessions.ModSessionTracker
import splice.upstream.codemode.ProcessDispatchers

private const val HISTORY_STOP_MS = 500L // why: a blocked history read cannot hold the daemon lock

/** The control listener opens first. A Bun host can subscribe once the old pairs have been recovered. */
internal class ModSessionTracking(
    private val scope: CoroutineScope,
    private val presence: StateFlow<Boolean>,
    private val sessions: ModSessionTracker,
    private val launches: ModLaunches,
    val starts: ModHandoverStarts,
) {
    val ready = CompletableDeferred<Unit>()
    private var work: Job? = null
    private var stopped = false

    init {
        sessions.follow(presence)
    }

    @Synchronized
    fun start() {
        if (stopped) return
        work = scope.launch(ProcessDispatchers().io()) {
            try {
                launches.restore()
                presence.collectLatest { installed ->
                    if (installed) {
                        if (!ready.isCompleted) {
                            sessions.restore(launches, starts.await())
                            ready.complete(Unit)
                        }
                        sessions.sweep()
                    }
                }
            } finally {
                ready.complete(Unit)
            }
        }
    }

    suspend fun stop() {
        sessions.stop()
        val history = fence()
        history?.cancel()
        withTimeoutOrNull(HISTORY_STOP_MS) { history?.join() }
        ready.complete(Unit)
    }

    @Synchronized
    private fun fence(): Job? {
        stopped = true
        return work
    }
}
