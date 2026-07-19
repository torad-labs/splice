// PORT-OF: server/src/upstream/gate.mjs @ 4ca99f7 — invariants: FIFO admission; maxInflight
// read FRESH per admission decision (live-PATCHable, 0 = unlimited — kotlinx Semaphore is
// banned here: it cannot hot-resize); Slot carries touch()/idleFor() for the watchdog;
// release is idempotent and admits the next waiter under the CURRENT limit.
// STRICT IMPROVEMENT (recorded in ledger, invisible to golden fixtures): a waiter cancelled
// while queued frees its queue spot via invokeOnCancellation — the Node gate's queued promise
// had no cancellation path and a dead request still consumed its FIFO turn.
// STRICT IMPROVEMENT (G21): the queue itself is now boundable via maxQueued (0 = unlimited,
// same convention as maxInflight) — overflow is signaled synchronously as
// GatewayAtCapacityException rather than growing the waiter queue without limit.
package splice.spi

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public class InflightGate(
    private val maxInflight: () -> Int,
    private val maxQueued: () -> Int = { 0 },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var inflight = 0
    private val queue = ArrayDeque<Waiter>()

    // A resumable FIFO cell. MUST be a plain class: queue.remove() matches by reference IDENTITY,
    // which is the whole point — a data class gives structural equality over mutable fields, which
    // is exactly why the prior version bolted on a synthetic `id` to undo it (craft review). So
    // UseDataClass is a FALSE POSITIVE here (a data class would reintroduce the bug); suppressed
    // with rationale, never a debt-hiding suppression. `resumed`/`continuation` are coordination.
    @Suppress("UseDataClass")
    private class Waiter(
        var resumed: Boolean = false,
        var continuation: CancellableContinuation<Unit>? = null,
    )

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

    private fun hasQueueCapacityLocked(): Boolean = maxQueued().let { it <= 0 || queue.size < it }

    private suspend fun awaitTurn() {
        val waiter = Waiter()
        var rejected = false
        suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                // capacity may have appeared between the fast path and here
                if (hasCapacityLocked() && queue.isEmpty()) {
                    inflight += 1
                    waiter.resumed = true
                } else if (hasQueueCapacityLocked()) {
                    waiter.continuation = cont
                    queue.addLast(waiter)
                } else {
                    rejected = true
                }
            }
            if (waiter.resumed) {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            if (rejected) {
                cont.resumeWithException(GatewayAtCapacityException())
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                // A queued (un-admitted) waiter just leaves the queue. An ADMITTED waiter's
                // inflight increment is compensated by the tryResume path in release() — the
                // admission and the hand-off race is decided there, never here (both sides run
                // under [lock]/tryResume atomicity, so exactly one compensator fires).
                synchronized(lock) { if (!waiter.resumed) queue.remove(waiter) }
            }
        }
    }

    internal fun release() {
        val toResume = synchronized(lock) {
            inflight -= 1
            drainAdmissibleLocked()
        }
        for (w in toResume) {
            val cont = w.continuation ?: continue
            // The admitted permit transfers ONLY if the waiter actually uses the resumption.
            // A waiter cancelled between admission and delivery would otherwise leak its
            // inflight slot permanently (each race shrinking the head's capacity by one until
            // it admits nothing). The onCancellation hook fires exactly in that case — hand
            // the permit back (the kotlinx Semaphore hand-off pattern).
            cont.resume(Unit) { _, _, _ -> release() }
        }
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

public class GatewayAtCapacityException : RuntimeException("gateway at capacity")
