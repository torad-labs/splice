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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    private val sources = ConcurrentHashMap<String, Slot>()

    /** A matching session may prepare one continuation on its already-held source permit. */
    public fun resumeSource(session: String?): Slot? = session?.let { sources[it]?.resume() }

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
        val session: String? = null,
        /** A ready source loan uses its existing counted permit, not an inflight increment. */
        var borrowed: Slot? = null,
    ) {
        fun turn(admitted: Boolean, immediate: Boolean, now: Long): Turn = when {
            !admitted -> Turn.Refused
            borrowed != null -> Turn.Borrowed(checkNotNull(borrowed))
            immediate -> Turn.Immediate
            else -> Turn.Queued(now - queuedAt)
        }
    }

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

    public suspend fun acquire(): Admission = acquire(null)

    /** Waits in the bounded queue if the previous source continuation is still releasing.
     * Its release retries the source loan, so a result never waits on its own reader's permit. */
    public suspend fun acquire(session: String?): Admission {
        // DR-147: DRAIN BEFORE SELF-ADMITTING. The fast path used to ask only "is there capacity?",
        // so after a live PATCH raised maxInflight nothing woke the waiters already parked — the
        // queue is drained solely by release(), and with every slot held by a long-lived SSE stream
        // there is no release to come. Newcomers were admitted straight past waiters that had been
        // queued for the whole backlog, so the operator's relief PATCH did nothing until a stream
        // ended, and the file's own "FIFO admission" and "live-PATCHable" claims were both false.
        // Draining under the CURRENT limit and self-admitting only behind an empty queue makes the
        // raise take effect immediately and keeps admission in arrival order.
        val (toResume, borrowed, admitted) = synchronized(lock) {
            val drained = drainAdmissibleLocked()
            val held = resumeSource(session)
            val canSelfAdmit = held == null && hasCapacityLocked() && queue.isEmpty()
            if (canSelfAdmit) inflight += 1
            Triple(drained, held, canSelfAdmit)
        }
        resumeAll(toResume)
        if (borrowed != null) return Admission.Acquired(borrowed)
        val turn = if (admitted) Turn.Immediate else awaitTurn(session)
        return turn.answer(this)
    }

    /** How [awaitTurn] ended: refused by the bounded queue, admitted on its recheck without
     *  queueing, or admitted after waiting in the queue for [Queued.waitedMs]. */
    private sealed class Turn {
        data object Refused : Turn()
        data object Immediate : Turn()
        data class Queued(val waitedMs: Long) : Turn()
        data class Borrowed(val slot: Slot) : Turn()

        fun answer(gate: InflightGate): Admission = when (this) {
            Refused -> Admission.AtCapacity
            Immediate -> Admission.Acquired(gate.deliver(null))
            is Queued -> Admission.Acquired(gate.deliver(waitedMs))
            is Borrowed -> Admission.Acquired(slot)
        }
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
            cont.resume(true) { _, _, _ -> returnUndelivered(w.borrowed) }
        }
    }

    private fun hasCapacityLocked(): Boolean {
        val limit = maxInflight()
        return limit <= 0 || inflight < limit
    }

    private fun hasQueueCapacityLocked(): Boolean = maxQueued().let { it <= 0 || queue.size < it }

    /** Whether, and how, this waiter came to hold a permit ([Turn]). */
    private suspend fun awaitTurn(session: String?): Turn {
        val waiter = Waiter(session = session)
        var admittedNow = false
        var rejected = false
        val admitted = suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                // Source release or fresh capacity may have appeared since the fast path.
                waiter.borrowed = resumeSource(session)
                if (waiter.borrowed != null) {
                    waiter.resumed = true
                    admittedNow = true
                } else if (hasCapacityLocked() && queue.isEmpty()) {
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
                cont.resume(true) { _, _, _ -> returnUndelivered(waiter.borrowed) }
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
        return waiter.turn(admitted, admittedNow, clock())
    }

    /** A delivered slot ends: it leaves the live list, is counted, and its permit goes back. */
    internal fun release(slot: Slot) {
        returnPermit {
            if (live.remove(slot)) released += 1
        }
    }

    /** A permit whose waiter was cancelled between admission and delivery goes back. It was never a
     *  slot, so nothing is counted and nothing leaves the live list. */
    private fun returnUndelivered(borrowed: Slot?) {
        if (borrowed != null) borrowed.release() else returnPermit {}
    }

    /** Returning a client handle may free a source loan without freeing the reader's counted permit. */
    private fun sourceAvailable() {
        val toResume = synchronized(lock) { drainAdmissibleLocked() }
        resumeAll(toResume)
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
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            val borrowed = resumeSource(next.session)
            if (borrowed == null && !hasCapacityLocked()) continue
            iterator.remove()
            next.borrowed = borrowed
            // DR-149: the `if (!next.resumed)` guard here was tautological — `resumed` is only ever
            // set on the admittedNow path, which never queues, or by a prior drain, which already
            // removed the waiter, so a QUEUED waiter always has it false. Worse, had it ever been
            // true the else silently dropped the waiter from the queue without resuming it, and
            // that request would hang forever. The flag itself stays: the cancellation hook reads
            // it to decide whether a waiter still owns a queue slot.
            next.resumed = true
            if (borrowed == null) inflight += 1
            admitted.add(next)
        }
        return admitted
    }

    public class Slot internal constructor(
        private val gate: InflightGate,
        private val clock: ElapsedClock,
        private val original: Slot? = null,
    ) {
        private val released = AtomicBoolean(false)
        private val state: InflightSlotState = original?.state ?: InflightSlotState(clock)

        /** A borrowed handle has its own release claim, but shares the source's one live permit. */
        public val resumedSource: Boolean get() = original != null

        /** Stable identity of the one counted permit, shared by every continuation handle. */
        public val countedSlot: Slot get() = original ?: this

        /** Names the turn this slot carries: [session] (the caller's short tag) then [model], or the
         *  model alone when the client sent no session, so two sessions on one model stay two tellable
         *  rows. A compaction keeps its model; [compact] is the flag that marks it. */
        public fun describe(model: String, compact: Boolean, session: String?) {
            state.compact = compact
            state.label = listOfNotNull(session, model).joinToString(" ")
        }

        public fun touch() {
            state.lastTouch.set(clock())
            state.streaming = true
        }

        /** Provider body bytes or a received WebSocket event, never headers or client keep-alives. */
        public fun received() {
            touch()
            state.upstreamBytes?.received()
        }

        /** Registered once when the turn is named, before its upstream drive starts. */
        public fun onReceived(observer: UpstreamBytes) {
            state.upstreamBytes = observer
        }

        /** The turn-owned receipt stamp, kept separate from this slot's watchdog liveness. */
        public fun interface UpstreamBytes {
            public fun received()
        }

        public fun idleForMs(): Long = clock() - state.lastTouch.get()

        internal fun reading(now: Long): GateSlot = GateSlot(
            label = state.label,
            compact = state.compact,
            phase = if (state.streaming) GatePhase.STREAMING else GatePhase.CONNECT,
            ageMs = now - state.admittedAt,
            idleMs = now - state.lastTouch.get(),
        )

        /** A raw source round keeps admission and the materialized request heap until it really ends. */
        public fun retain(): Lease = retainSource(null)

        /** The session header identifies a possible continuation before request materialization. */
        public fun retainSource(session: String?): Lease = synchronized(state.ownership) {
            check(!released.get()) { "admission owner already released" }
            state.owners++
            if (session != null) {
                state.readers++
                gate.sources[session] = original ?: this
            }
            Lease(this, session)
        }

        internal fun resume(): Slot? = synchronized(state.ownership) {
            val root = original ?: this
            val available = state.readers > 0 && !state.continuation && !state.finalized
            if (!root.released.get() || !available) {
                null
            } else {
                state.owners++
                state.continuation = true
                Slot(gate, clock, root)
            }
        }

        public class Lease internal constructor(private val slot: Slot, private val session: String?) {
            private val released = AtomicBoolean(false)

            public fun release() {
                if (released.compareAndSet(false, true)) slot.releaseOwner(session)
            }
        }

        public fun release() {
            if (released.compareAndSet(false, true)) releaseOwner(continuation = resumedSource)
        }

        private fun releaseOwner(session: String? = null, continuation: Boolean = false) {
            val root = original ?: this
            val last = synchronized(state.ownership) {
                if (session != null && --state.readers == 0) gate.sources.remove(session, root)
                if (continuation) state.continuation = false
                state.owners--
                (state.owners == 0).also { if (it) state.finalized = true }
            }
            if (last) {
                gate.release(root)
                drainOnRelease()
            } else {
                gate.sourceAvailable()
            }
        }

        /** V4-165: [end] runs once, when this slot is released — or now, if it already was. The
         *  slot is the one object whose release already means "this turn is over" on every path
         *  (a detached compaction drive takes it along), so a turn's end is heard here rather than
         *  re-derived at each exit. A registration racing the release still runs exactly once: each
         *  entry is polled off the queue by whichever drain reaches it first. */
        public fun onRelease(end: TurnEnd) {
            state.onRelease.add(end)
            if (state.finalized) drainOnRelease()
        }

        private fun drainOnRelease() {
            generateSequence { state.onRelease.poll() }.forEach { it.ended() }
        }
    }
}
