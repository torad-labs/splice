// NEW: per-turn performance telemetry (the bottleneck instrument). ONE TurnPerf is created at
// request arrival and rides the whole turn; stages record COMPLETION marks (ms since arrival,
// monotone along the pipeline: recv -> parse -> build -> gate -> headers -> first_byte ->
// first_frame -> first_delta -> stream_end -> finish) and counters record sums/sizes/attempts
// (auth_ms, write_ms, bytes, retries...). Key names are single-sourced in PerfKeys — the log
// line, the JSONL row, and the control-plane aggregation all read THESE names; renaming a key
// orphans its history. Recording is best-effort telemetry: it must never throw into the turn.
package splice.core.perf

import splice.core.util.ElapsedClock
import splice.core.util.WallClock

/**
 * A span of turn work whose DURATION is the measurement — the thing [TurnPerf.timed] brackets.
 *
 * Named rather than left a bare `suspend () -> T` (HD-22) because the seam is not "any function":
 * it is the region an [ElapsedClock] difference is attributed to, and the counter name passed
 * beside it is a claim about what ran inside. The `finally` in [TurnPerf.timed] is the contract —
 * a span that throws is still charged to its counter, so a failed auth still shows up as
 * `auth_ms` rather than vanishing from the row.
 *
 * NOT inlined, so there is no non-local return to lose: `timed` was already allocating a lambda
 * object per call and the only change is that the object now has a name.
 */
public fun interface TimedWork<T> {
    public suspend operator fun invoke(): T
}

// PerfKeys lives in PerfKeys.kt; PerfSnapshot + TurnPerfTiming live in
// PerfSnapshot.kt (concentration, 2026-08-19).

/** [ElapsedClock] owns durations; [WallClock] anchors their epoch starts once per turn.
 *  Later wall-clock changes cannot move a retained interval or alter its duration.
 *  Production always passes the head's monotonic clock explicitly — `TurnPerf(clock = clock)` at
 *  HeadServer.handleMessages, off `HeadDeps.clock` = `MonoClock::nowMs` — so the wall-clock DEFAULT
 *  below is reached only by tests that construct a bare `TurnPerf()`. It is left as it was rather
 *  than quietly retuned to `MonoClock::nowMs`: that would be a behaviour change on a frozen tree,
 *  and it belongs to whoever measures it, not to a typing wave. */
public class TurnPerf(
    wallClock: WallClock = WallClock(System::currentTimeMillis),
    // Last, so a trailing lambda is the elapsed clock: `TurnPerf { now }`.
    private val clock: ElapsedClock = ElapsedClock(System::currentTimeMillis),
) {
    private val startedAt: Long = clock()
    private val startedAtEpochMs: Long = wallClock()
    private val lock = TurnPerfLock()
    private val marks = LinkedHashMap<String, Long>()
    private val counters = LinkedHashMap<String, Long>()
    private var arrivalOffsetMs = 0L
    private var upstreamAttempt = 0L
    private var upstreamGapEnd: UpstreamGapEnd? = null

    /** Paired duration and epoch observations publish into the same atomic snapshot. */
    public val intervals: PerfIntervals = PerfIntervals(
        lock,
        counters,
        startedAtEpochMs,
        { it == upstreamAttempt },
        ::maxCount,
    )

    /** Anchor arrival-relative durations; legacy stage marks keep their original origin. */
    public fun recordArrival(at: Long) {
        synchronized(lock) { arrivalOffsetMs = startedAt - at }
    }

    /** Called only after actual nonempty client bytes have been written and flushed successfully. */
    public fun firstClientByte() {
        val at = elapsedMs()
        synchronized(lock) {
            if (PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS !in counters) {
                counters[PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS] = at + arrivalOffsetMs
            }
        }
    }

    public fun elapsedMs(): Long = clock() - startedAt

    /** Origin for converting an existing reading from the same monotonic clock to elapsed milliseconds. */
    public val clockOriginMs: Long get() = startedAt

    /** Arrival-relative clock for transport milestones; legacy stage marks retain their origin. */
    public fun arrivalElapsedMs(): Long {
        val at = elapsedMs()
        return synchronized(lock) { at + arrivalOffsetMs }
    }

    /** Retain each attempt start while atomically discarding its predecessor's timing measurements. */
    public fun beginUpstreamAttempt(): Long = synchronized(lock) {
        for (milestones in UpstreamMilestones.entries) {
            counters.remove(milestones.arrival)
            counters.remove(milestones.wait)
        }
        ++upstreamAttempt
        counters[PerfKeys.TRANSPORT_ATTEMPT_STARTS] = upstreamAttempt
        upstreamAttempt
    }

    /** Publish only the current attempt's observed pair. Legacy SSE parameter names and JVM overload remain. */
    @JvmOverloads
    public fun recordUpstreamTiming(
        attempt: Long,
        writtenAt: Long?,
        firstByteAt: Long?,
        milestones: UpstreamMilestones = UpstreamMilestones.SSE_WRITE,
    ) {
        synchronized(lock) {
            if (attempt != upstreamAttempt || writtenAt == null) return
            counters[milestones.arrival] = writtenAt
            counters.remove(milestones.wait)
            if (firstByteAt != null && firstByteAt >= writtenAt) {
                counters[milestones.wait] = firstByteAt - writtenAt
            }
        }
    }

    /** Record [stage] completion at now. Re-marking overwrites (retry loops: last attempt wins). */
    public fun mark(stage: String): Long {
        val at = elapsedMs()
        synchronized(lock) { marks[stage] = at }
        return at
    }

    /** Record [stage] only the first time (first_byte / first_delta family). */
    public fun markOnce(stage: String) {
        val at = elapsedMs()
        synchronized(lock) { if (stage !in marks) marks[stage] = at }
    }

    /** True once [stage] has been marked — G5 reads this on FIRST_FRAME to distinguish "handed
     *  off to the block" from "client actually saw a byte", gating stream-torn-before-first-frame
     *  reissue from the hard no-retry-after-output rule. */
    public fun hasMark(stage: String): Boolean = synchronized(lock) { stage in marks }

    /** One counter's running value, 0 when it was never written. The READ COUNTERPART of [add], and
     *  the same shape as [hasMark] is to [mark]: a LIVE surface asks for one or two keys while the
     *  turn is still running, and [snapshot] would copy every mark and every counter to answer that.
     *  The console's live-turn list reads two keys per turn on every poll (LiveTurns), so the copy
     *  would be the whole telemetry of every open turn, several times a second, for two numbers. */
    public fun count(counter: String): Long = synchronized(lock) { counters[counter] ?: 0L }

    public fun add(counter: String, delta: Long) {
        if (delta == 0L) return
        synchronized(lock) { counters[counter] = (counters[counter] ?: 0L) + delta }
    }

    public fun setCount(counter: String, value: Long) {
        synchronized(lock) { counters[counter] = value }
    }

    /** Keep a maximum and, for the upstream gap, its event kind atomically. Ties keep the first kind. */
    public fun maxCount(counter: String, value: Long, end: UpstreamGapEnd = UpstreamGapEnd.UNKNOWN) {
        synchronized(lock) {
            val previous = counters[counter]
            if (previous == null || value > previous) {
                counters[counter] = value
                intervals.unmeasured(counter)
                if (counter == PerfKeys.UP_GAP_MAX_MS) upstreamGapEnd = end
            }
        }
    }

    /** Time a suspending [block] into [counter] (summed across calls). */
    public suspend fun <T> timed(counter: String, block: TimedWork<T>): T {
        val t0 = clock()
        try {
            return block()
        } finally {
            add(counter, clock() - t0)
        }
    }

    public fun snapshot(): PerfSnapshot = synchronized(lock) {
        PerfSnapshot(LinkedHashMap(marks), LinkedHashMap(counters), upstreamGapEnd)
    }
}
