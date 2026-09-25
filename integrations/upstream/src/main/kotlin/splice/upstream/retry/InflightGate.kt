// PORT-OF: server/src/upstream/gate.mjs @ pre-public-port-baseline — invariants: FIFO admission; maxInflight
// read FRESH per admission decision (live-PATCHable, 0 = unlimited — kotlinx Semaphore is
// banned here: it cannot hot-resize); Slot carries touch()/idleFor() for the watchdog;
// release is idempotent and admits the next waiter under the CURRENT limit.
// STRICT IMPROVEMENT (recorded in ledger, invisible to golden fixtures): a waiter cancelled
// while queued frees its queue spot via invokeOnCancellation — the Node gate's queued promise
// had no cancellation path and a dead request still consumed its FIFO turn.
// STRICT IMPROVEMENT (G21): the queue itself is now boundable via maxQueued (0 = unlimited,
// same convention as maxInflight) — overflow is answered synchronously as
// [InflightGate.Admission.AtCapacity] rather than growing the waiter queue without limit.
// V4-114: that overflow used to be a thrown GatewayAtCapacityException. `acquire` ANSWERS a
// question — "do I get a slot" — so both answers ride its return type and the caller's `when` is
// compiler-checked (kt-no-exception-as-outcome). Nothing about FIFO order or the permit hand-off
// changed; only the channel the refusal travels on.
// V4-213: the gate MEASURES what the Node gate reported and this port had dropped to literals —
// acquired/released/waited, the mean queue wait, and one live reading per slot it holds (label,
// compact, phase connect|streaming, age, idle) — and [snapshot] takes all of it under the lock.
// A slot counts as acquired, and is listed, only once its permit is DELIVERED: a waiter cancelled
// between admission and delivery hands its permit back through [returnUndelivered] and never
// appears, and a waiter cancelled while queued never reaches either.
package splice.upstream.retry

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import splice.core.head.GatePhase
import splice.core.head.GateSlot
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.upstream.TurnEnd
import splice.upstream.Waiter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

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

    // V4-213, all guarded by [lock]. Insertion order is admission order, so [snapshot] lists the
    // oldest slot first (the console reads the first live row as a head's current turn).
    private val live = LinkedHashSet<Slot>()
    private var acquired = 0L
    private var released = 0L
    private var waited = 0L
    private var waitMsTotal = 0L

    // A resumable FIFO cell. MUST be a plain class: queue.remove() matches by reference IDENTITY,
    // which is the whole point — a data class gives structural equality over mutable fields, which
    // is exactly why the prior version bolted on a synthetic `id` to undo it (craft review). So
    // UseDataClass is a FALSE POSITIVE here (a data class would reintroduce the bug); suppressed
    // with rationale, never a debt-hiding suppression. `resumed`/`continuation` are coordination.
    @Suppress("UseDataClass")
    private class Waiter(
        var resumed: Boolean = false,
        var continuation: CancellableContinuation<Boolean>? = null,
        /** When it entered the queue, on the gate's clock: its wait is measured from here. */
        var queuedAt: Long = 0L,
    )

    public data class Snapshot(
        val inflight: Int,
        val queued: Int,
        val limit: Int,
        /** Slots delivered, and slots released, since the gate was built. */
        val acquired: Long,
        val released: Long,
        /** Deliveries that waited in the queue first, and their mean wait, rounded (0 while none has). */
        val waited: Long,
        val avgWaitMs: Long,
        /** One reading per slot held, oldest first. */
        val live: List<GateSlot>,
    )

    public fun snapshot(): Snapshot = synchronized(lock) {
        val now = clock()
        Snapshot(
            inflight = inflight,
            queued = queue.size,
            limit = maxInflight(),
            acquired = acquired,
            released = released,
            waited = waited,
            avgWaitMs = if (waited == 0L) 0L else (waitMsTotal + waited / 2) / waited,
            live = live.map { it.reading(now) },
        )
    }

    /** What [acquire] answers. The refusal is a VALUE, not a throw: a `RuntimeException` subclass
     *  the caller had to know to catch by name was indistinguishable at any broad catch from a real
     *  invariant break, and no signature announced that a refusal existed (V4-114). */
    public sealed class Admission {
        /** The admitted permit. [slot] MUST be released exactly once. [ConsistentCopyVisibility]: the
         *  generated copy() is as internal as the constructor, so no caller mints a second permit. */
        @ConsistentCopyVisibility
        public data class Acquired internal constructor(public val slot: Slot) : Admission()

        /** maxQueued is full: this request was never queued and holds no permit. */
        public data object AtCapacity : Admission()
    }

    public suspend fun acquire(): Admission {
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
        val waitedMs = if (admitted) {
            null
        } else {
            when (val turn = awaitTurn()) {
                Turn.Refused -> return Admission.AtCapacity
                Turn.Immediate -> null
                is Turn.Queued -> turn.waitedMs
            }
        }
        return Admission.Acquired(deliver(waitedMs))
    }

    /** How [awaitTurn] ended: refused by the bounded queue, admitted on its recheck without
     *  queueing, or admitted after waiting in the queue for [Queued.waitedMs]. */
    private sealed interface Turn {
        data object Refused : Turn
        data object Immediate : Turn
        data class Queued(val waitedMs: Long) : Turn
    }

    /** A delivered permit becomes a live slot: counted, its wait added when it queued, and listed
     *  until it is released. Only a caller that got its permit reaches here. */
    private fun deliver(waitedMs: Long?): Slot {
        val slot = Slot(this, clock)
        synchronized(lock) {
            acquired += 1
            if (waitedMs != null) {
                waited += 1
                waitMsTotal += waitedMs
            }
            live.add(slot)
        }
        return slot
    }

    /** The one hand-off. The admitted permit transfers ONLY if the waiter actually uses the
     *  resumption: a waiter cancelled between admission and delivery would otherwise leak its
     *  inflight slot permanently, shrinking the head's capacity by one per race until it admits
     *  nothing. DR-147 routes acquire's drain through the SAME helper release() uses, so the two
     *  drain sites cannot drift on that compensation. */
    private fun resumeAll(waiters: List<Waiter>) {
        for (w in waiters) {
            val cont = w.continuation ?: continue
            cont.resume(true) { _, _, _ -> returnUndelivered() }
        }
    }

    private fun hasCapacityLocked(): Boolean {
        val limit = maxInflight()
        return limit <= 0 || inflight < limit
    }

    private fun hasQueueCapacityLocked(): Boolean = maxQueued().let { it <= 0 || queue.size < it }

    /** Whether, and how, this waiter came to hold a permit ([Turn]). */
    private suspend fun awaitTurn(): Turn {
        val waiter = Waiter()
        var admittedNow = false
        var rejected = false
        val admitted = suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                // capacity may have appeared between the fast path and here
                if (hasCapacityLocked() && queue.isEmpty()) {
                    inflight += 1
                    waiter.resumed = true
                    admittedNow = true
                } else if (hasQueueCapacityLocked()) {
                    waiter.continuation = cont
                    waiter.queuedAt = clock()
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
                // Same undelivered-handler as resumeAll(): a waiter cancelled between inflight++ and
                // delivery must return the permit or the head permanently loses one capacity slot.
                cont.resume(true) { _, _, _ -> returnUndelivered() }
                return@suspendCancellableCoroutine
            }
            if (rejected) {
                // No permit was taken, so there is nothing to compensate on cancellation.
                cont.resume(false)
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
        return turnOf(admitted, admittedNow, waiter)
    }

    private fun turnOf(admitted: Boolean, immediate: Boolean, waiter: Waiter): Turn = when {
        !admitted -> Turn.Refused
        immediate -> Turn.Immediate
        else -> Turn.Queued(clock() - waiter.queuedAt)
    }

    /** A delivered slot ends: it leaves the live list, is counted, and its permit goes back. */
    internal fun release(slot: Slot) {
        returnPermit {
            if (live.remove(slot)) released += 1
        }
    }

    /** A permit whose waiter was cancelled between admission and delivery goes back. It was never a
     *  slot, so nothing is counted and nothing leaves the live list. */
    private fun returnUndelivered() {
        returnPermit {}
    }

    private inline fun returnPermit(locked: () -> Unit) {
        val toResume = synchronized(lock) {
            locked()
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
        private val admittedAt = clock()
        private val lastTouch = AtomicLong(admittedAt)
        private val onRelease = ConcurrentLinkedQueue<TurnEnd>()

        // V4-213: what the live reading says about this slot. The slot is taken BEFORE the request
        // body is read (HeadAdmission), so the turn names itself through [describe] once it is
        // prepared; until then it reads [UNREAD_LABEL]. Every touch comes from the upstream (its 2xx,
        // its bytes, its WebSocket frames), so the first one is the phase's move to streaming.
        @Volatile private var label: String = UNREAD_LABEL

        @Volatile private var compact: Boolean = false

        @Volatile private var streaming: Boolean = false

        /** Names the turn this slot carries: `compact` for a compaction, else [model]. */
        public fun describe(model: String, compact: Boolean) {
            this.compact = compact
            label = if (compact) COMPACT_LABEL else model
        }

        public fun touch() {
            lastTouch.set(clock())
            streaming = true
        }

        public fun idleForMs(): Long = clock() - lastTouch.get()

        internal fun reading(now: Long): GateSlot = GateSlot(
            label = label,
            compact = compact,
            phase = if (streaming) GatePhase.STREAMING else GatePhase.CONNECT,
            ageMs = now - admittedAt,
            idleMs = now - lastTouch.get(),
        )

        public fun release() {
            if (released.compareAndSet(false, true)) {
                gate.release(this)
                drainOnRelease()
            }
        }

        /** V4-165: [end] runs once, when this slot is released — or now, if it already was. The
         *  slot is the one object whose release already means "this turn is over" on every path
         *  (a detached compaction drive takes it along), so a turn's end is heard here rather than
         *  re-derived at each exit. A registration racing the release still runs exactly once: each
         *  entry is polled off the queue by whichever drain reaches it first. */
        public fun onRelease(end: TurnEnd) {
            onRelease.add(end)
            if (released.get()) drainOnRelease()
        }

        private fun drainOnRelease() {
            generateSequence { onRelease.poll() }.forEach { it.ended() }
        }
    }
}

/** The live label of a compaction turn, and of a slot whose request has not been read yet: the Node
 *  gate's own two words (codex-proxy.mjs: `compactMode ? 'compact' : (model || 'req')`). */
private const val COMPACT_LABEL = "compact"
private const val UNREAD_LABEL = "req"
