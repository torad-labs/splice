// PORT-OF: server/src/upstream/gate.mjs @ pre-public-port-baseline — invariants: FIFO admission; maxInflight
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
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A concurrency limit READ FRESH at every admission decision, never captured at construction.
 *
 * That freshness is the port's entire reason to exist and the reason kotlinx `Semaphore` is banned
 * in [InflightGate]: a live `PATCH` of `maxInflight` must change the next admission without a head
 * restart, and a semaphore cannot hot-resize. `0` means unlimited, the same convention for both
 * limits.
 *
 * Distinct from a GAUGE (`ControlServer.failedHeads`), which is also `() -> Int` but reports what
 * IS rather than bounding what may happen next.
 */
public fun interface LiveLimit {
    public operator fun invoke(): Int
}

public class InflightGate(
    private val maxInflight: LiveLimit,
    private val maxQueued: LiveLimit = LiveLimit { 0 },
    // Default is monotonic — wall-clock jumps must not invent idle timeouts or freeze slots.
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
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
        // DR-147: DRAIN BEFORE SELF-ADMITTING. The fast path used to ask only "is there capacity?",
        // so after a live PATCH raised maxInflight nothing woke the waiters already parked — the
        // queue is drained solely by release(), and with every slot held by a long-lived SSE stream
        // there is no release to come. Newcomers were admitted straight past waiters that had been
        // queued for the whole backlog, so the operator's relief PATCH did nothing until a stream
        // ended, and the file's own "FIFO admission" and "live-PATCHable" claims were both false.
        // Draining under the CURRENT limit and self-admitting only behind an empty queue makes the
        // raise take effect immediately and keeps admission in arrival order.
        val (toResume, admitted) = synchronized(lock) {
            val drained = drainAdmissibleLocked()
            val canSelfAdmit = hasCapacityLocked() && queue.isEmpty()
            if (canSelfAdmit) inflight += 1
            drained to canSelfAdmit
        }
        resumeAll(toResume)
        if (!admitted) awaitTurn()
        return Slot(this, clock)
    }

    /** The one hand-off. The admitted permit transfers ONLY if the waiter actually uses the
     *  resumption: a waiter cancelled between admission and delivery would otherwise leak its
     *  inflight slot permanently, shrinking the head's capacity by one per race until it admits
     *  nothing. DR-147 routes acquire's drain through the SAME helper release() uses, so the two
     *  drain sites cannot drift on that compensation. */
    private fun resumeAll(waiters: List<Waiter>) {
        for (w in waiters) {
            val cont = w.continuation ?: continue
            cont.resume(Unit) { _, _, _ -> release() }
        }
    }

    private fun hasCapacityLocked(): Boolean {
        val limit = maxInflight()
        return limit <= 0 || inflight < limit
    }

    private fun hasQueueCapacityLocked(): Boolean = maxQueued().let { it <= 0 || queue.size < it }

    private suspend fun awaitTurn() {
        val waiter = Waiter()
        var admittedNow = false
        var rejected = false
        suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                // capacity may have appeared between the fast path and here
                if (hasCapacityLocked() && queue.isEmpty()) {
                    inflight += 1
                    waiter.resumed = true
                    admittedNow = true
                } else if (hasQueueCapacityLocked()) {
                    waiter.continuation = cont
                    queue.addLast(waiter)
                } else {
                    rejected = true
                }
            }
            // Resume from THIS thread only when the recheck above admitted synchronously.
            // `waiter.resumed` is the wrong guard here: a releaser can drain the just-queued
            // waiter and resume it BETWEEN the lock exit and this line, and reading the shared
            // flag then double-resumes the continuation ("Already resumed" ISE — caught by the
            // cancel-racing-admission hammer test on CI). Only the local flag is race-free.
            if (admittedNow) {
                // Same undelivered-handler as release(): a waiter cancelled between inflight++ and
                // delivery must return the permit or the head permanently loses one capacity slot.
                cont.resume(Unit) { _, _, _ -> release() }
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
        resumeAll(toResume)
    }

    // ported drain loop: skip-resumed + capacity guard
    private fun drainAdmissibleLocked(): List<Waiter> {
        val admitted = mutableListOf<Waiter>()
        while (queue.isNotEmpty() && hasCapacityLocked()) {
            val next = queue.pollFirst() ?: break
            // DR-149: the `if (!next.resumed)` guard here was tautological — `resumed` is only ever
            // set on the admittedNow path, which never queues, or by a prior drain, which already
            // removed the waiter, so a QUEUED waiter always has it false. Worse, had it ever been
            // true the else silently dropped the waiter from the queue without resuming it, and
            // that request would hang forever. The flag itself stays: the cancellation hook reads
            // it to decide whether a waiter still owns a queue slot.
            next.resumed = true
            inflight += 1
            admitted.add(next)
        }
        return admitted
    }

    public class Slot internal constructor(
        private val gate: InflightGate,
        private val clock: ElapsedClock,
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
