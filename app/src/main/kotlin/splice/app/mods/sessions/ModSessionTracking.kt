package splice.app.mods.sessions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import splice.upstream.codemode.ProcessDispatchers

/** The control listener opens first. A Bun host can subscribe once the old pairs have been recovered. */
internal class ModSessionTracking(
    private val scope: CoroutineScope,
    private val presence: StateFlow<Boolean>,
    private val sessions: ModSessionTracker,
    private val launches: ModLaunches,
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
                            sessions.restore(launches)
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
        fence()?.cancelAndJoin()
        sessions.stop()
        ready.complete(Unit)
    }

    @Synchronized
    private fun fence(): Job? {
        stopped = true
        return work
    }
}
