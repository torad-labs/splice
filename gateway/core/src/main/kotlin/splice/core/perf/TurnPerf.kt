// NEW: per-turn performance telemetry (the bottleneck instrument). ONE TurnPerf is created at
// request arrival and rides the whole turn; stages record COMPLETION marks (ms since arrival,
// monotone along the pipeline: recv -> parse -> build -> gate -> headers -> first_byte ->
// first_frame -> first_delta -> stream_end -> finish) and counters record sums/sizes/attempts
// (auth_ms, write_ms, bytes, retries...). Key names are single-sourced in PerfKeys — the log
// line, the JSONL row, and the control-plane aggregation all read THESE names; renaming a key
// orphans its history. Recording is best-effort telemetry: it must never throw into the turn.
package splice.core.perf

import splice.core.util.ElapsedClock

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

/** Every reading here is consumed as a DIFFERENCE (`clock() - startedAt`, `clock() - t0`) and none
 *  is ever persisted or put on the wire, so this is an [ElapsedClock] seam, not a [WallClock] one.
 *  Production always passes the head's monotonic clock explicitly — `TurnPerf(clock)` at
 *  HeadServer.handleMessages, off `HeadDeps.clock` = `MonoClock::nowMs` — so the wall-clock DEFAULT
 *  below is reached only by tests that construct a bare `TurnPerf()`. It is left as it was rather
 *  than quietly retuned to `MonoClock::nowMs`: that would be a behaviour change on a frozen tree,
 *  and it belongs to whoever measures it, not to a typing wave. */
public class TurnPerf(private val clock: ElapsedClock = ElapsedClock(System::currentTimeMillis)) {

    private val startedAt: Long = clock()
    private val lock = Any()
    private val marks = LinkedHashMap<String, Long>()
    private val counters = LinkedHashMap<String, Long>()

    public fun elapsedMs(): Long = clock() - startedAt

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

    public fun add(counter: String, delta: Long) {
        if (delta == 0L) return
        synchronized(lock) { counters[counter] = (counters[counter] ?: 0L) + delta }
    }

    public fun setCount(counter: String, value: Long) {
        synchronized(lock) { counters[counter] = value }
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
        PerfSnapshot(LinkedHashMap(marks), LinkedHashMap(counters))
    }
}
