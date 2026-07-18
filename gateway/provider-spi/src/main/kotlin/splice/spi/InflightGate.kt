// PORT-OF: server/src/upstream/gate.mjs @ 4ca99f7 — invariants: FIFO admission; maxInflight
// read FRESH per admission decision (live-PATCHable, 0 = unlimited — kotlinx Semaphore is
// banned here: it cannot hot-resize); Slot carries touch()/idleFor() for the watchdog;
// release is idempotent and admits the next waiter under the CURRENT limit.
// STRICT IMPROVEMENT (recorded in ledger, invisible to golden fixtures): a waiter cancelled
// while queued frees its queue spot via invokeOnCancellation — the Node gate's queued promise
// had no cancellation path and a dead request still consumed its FIFO turn.
package splice.spi

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

public class InflightGate(
    private val maxInflight: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var inflight = 0
    private val queue = ArrayDeque<Waiter>()

    // A resumable FIFO cell: `id` is a unique tie-break that keeps structural equality identity-safe
    // for queue.remove(); `resumed` + `continuation` are the mutable coordination state.
    private data class Waiter(
        val id: Long,
        var resumed: Boolean = false,
        var continuation: CancellableContinuation<Unit>? = null,
    )

    private var nextWaiterId = 0L

    public data class Snapshot(val inflight: Int, val queued: Int, val limit: Int)

    public fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(inflight = inflight, queued = queue.size, limit = maxInflight())
    }

    public suspend fun acquire(): Slot {
        val admitted = synchronized(lock) {
            if (hasCapacityLocked()) {
                inflight += 1
                true
            } else {
                false
            }
        }
        if (!admitted) awaitTurn()
        return Slot(this, clock)
    }

    private fun hasCapacityLocked(): Boolean {
        val limit = maxInflight()
        return limit <= 0 || inflight < limit
    }

    private suspend fun awaitTurn() {
        val waiter = Waiter(nextWaiterId++)
        suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                // capacity may have appeared between the fast path and here
                if (hasCapacityLocked() && queue.isEmpty()) {
                    inflight += 1
                    waiter.resumed = true
                } else {
                    waiter.continuation = cont
                    queue.addLast(waiter)
                }
            }
            if (waiter.resumed) {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                synchronized(lock) { queue.remove(waiter) }
            }
        }
    }

    internal fun release() {
        val toResume = synchronized(lock) {
            inflight -= 1
            drainAdmissibleLocked()
        }
        toResume.forEach { it.continuation?.resume(Unit) }
    }

    // ported drain loop: skip-resumed + capacity guard
    private fun drainAdmissibleLocked(): List<Waiter> {
        val admitted = mutableListOf<Waiter>()
        while (queue.isNotEmpty() && hasCapacityLocked()) {
            val next = queue.pollFirst() ?: break
            if (!next.resumed) {
                next.resumed = true
                inflight += 1
                admitted.add(next)
            }
        }
        return admitted
    }

    public class Slot internal constructor(
        private val gate: InflightGate,
        private val clock: () -> Long,
    ) {
        private val released = AtomicBoolean(false)
        private val lastTouch = AtomicLong(clock())

        public fun touch() {
            lastTouch.set(clock())
        }

        public fun idleForMs(): Long = clock() - lastTouch.get()

        public fun release() {
            if (released.compareAndSet(false, true)) gate.release()
        }
    }
}
