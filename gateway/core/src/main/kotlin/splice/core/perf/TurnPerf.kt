// NEW: per-turn performance telemetry (the bottleneck instrument). ONE TurnPerf is created at
// request arrival and rides the whole turn; stages record COMPLETION marks (ms since arrival,
// monotone along the pipeline: recv -> parse -> build -> gate -> headers -> first_byte ->
// first_frame -> first_delta -> stream_end -> finish) and counters record sums/sizes/attempts
// (auth_ms, write_ms, bytes, retries...). Key names are single-sourced in PerfKeys — the log
// line, the JSONL row, and the control-plane aggregation all read THESE names; renaming a key
// orphans its history. Recording is best-effort telemetry: it must never throw into the turn.
package splice.core.perf

/** The single source of every perf field name (marks are *_ms-since-arrival; counters are raw). */
public object PerfKeys {
    // stage completion marks (ms since arrival)
    public const val RECV: String = "recv"
    public const val PARSE: String = "parse"
    public const val BUILD: String = "build"
    public const val GATE: String = "gate"
    public const val HEADERS: String = "headers"
    public const val FIRST_BYTE: String = "first_byte"
    public const val FIRST_FRAME: String = "first_frame"
    public const val FIRST_DELTA: String = "first_delta"
    public const val STREAM_END: String = "stream_end"
    public const val FINISH: String = "finish"
    public const val TOTAL: String = "total"

    // counters (durations are summed ms; sizes are bytes; the rest are counts)
    public const val AUTH_MS: String = "auth_ms"
    public const val BACKOFF_MS: String = "backoff_ms"
    public const val REFRESH_MS: String = "refresh_ms"
    public const val WRITE_MS: String = "write_ms"
    public const val USAGE_MS: String = "usage_ms"
    public const val ATTEMPTS: String = "attempts"
    public const val RETRIES: String = "retries"
    public const val REFRESHES: String = "refreshes"
    public const val REQ_BYTES: String = "req_bytes"
    public const val UPSTREAM_REQ_BYTES: String = "upstream_req_bytes"
    public const val SSE_BYTES_IN: String = "sse_bytes_in"
    public const val EVENTS_IN: String = "events_in"
    public const val FRAMES_OUT: String = "frames_out"
    public const val BYTES_OUT: String = "bytes_out"
    public const val OUT_TOKENS: String = "out_tokens"
    public const val IN_TOKENS: String = "in_tokens"
    public const val CACHED_TOKENS: String = "cached_tokens"

    /** Concurrent turns in flight on this head at admission — the live-concurrency gauge. */
    public const val INFLIGHT: String = "inflight"

    /** Mark keys in pipeline order — the aggregation and the log line render in THIS order. */
    public val markOrder: List<String> = listOf(
        RECV, PARSE, BUILD, GATE, HEADERS, FIRST_BYTE, FIRST_FRAME, FIRST_DELTA,
        STREAM_END, FINISH, TOTAL,
    )
}

/** Immutable view of a turn's recorded telemetry. */
public data class PerfSnapshot(
    val marks: Map<String, Long>,
    val counters: Map<String, Long>,
)

public class TurnPerf(private val clock: () -> Long = System::currentTimeMillis) {

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
    public suspend fun <T> timed(counter: String, block: suspend () -> T): T {
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

/** Time a suspending [block] into [counter] when perf is wired; run it plain otherwise. */
public suspend fun <T> TurnPerf?.timedOr(counter: String, block: suspend () -> T): T =
    if (this == null) block() else timed(counter, block)

/**
 * The one-line perf summary: marks in pipeline order, then counters, skipping absent fields.
 * `[codex] perf outcome=ok compact=false model=m recv=3 ... | auth_ms=1 ... out_tokens=850`
 */
public fun perfLine(head: String, outcome: String, compact: Boolean, model: String, snap: PerfSnapshot): String {
    val markPart = PerfKeys.markOrder
        .mapNotNull { k -> snap.marks[k]?.let { "$k=$it" } }
        .joinToString(" ")
    val counterPart = snap.counters.entries.joinToString(" ") { (k, v) -> "$k=$v" }
    return buildString {
        append("[").append(head).append("] perf outcome=").append(outcome)
        append(" compact=").append(compact)
        append(" model=").append(model)
        if (markPart.isNotEmpty()) append(" ").append(markPart)
        if (counterPart.isNotEmpty()) append(" | ").append(counterPart)
        append("\n")
    }
}
