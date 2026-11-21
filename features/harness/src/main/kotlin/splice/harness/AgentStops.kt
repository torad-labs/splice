// NEW: the agent's call (docs/specs/harness.md, decisions 6 and 12). The head drains or stops: the work of every call
// the model made stops, and the turn goes on as today, with the model told the capability stopped. From then until the
// head runs again, a call is not worked at all.
package splice.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import splice.harness.agent.Settled
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The work of the calls one message of the model made, as each settles. */
internal fun interface CallsWork {
    suspend operator fun invoke(): List<Settled>
}

internal class AgentStops {
    private val stopping = AtomicBoolean(false)
    private val waits: MutableSet<CompletableDeferred<Unit>> = ConcurrentHashMap.newKeySet()

    /** The head stops: every call's work stops, and none is worked until [started]. */
    fun stop() {
        stopping.set(true)
        waits.forEach { it.complete(Unit) }
    }

    fun started() {
        stopping.set(false)
    }

    /** What [work] comes to, or null when the head stopped first. */
    suspend fun raced(work: CallsWork): List<Settled>? {
        val stop = CompletableDeferred<Unit>()
        waits += stop
        if (stopping.get()) stop.complete(Unit)
        return try {
            coroutineScope {
                val done = async { work() }
                val stopped = async { stop.await() }
                try {
                    select<List<Settled>?> {
                        done.onAwait { it }
                        stopped.onAwait { null }
                    }
                } finally {
                    done.cancel()
                    stopped.cancel()
                }
            }
        } finally {
            waits -= stop
        }
    }
}
