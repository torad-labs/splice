// NEW: the three SSE reader seams. Split from SseReader.kt so the
// line/event walk is not billed for the fun-interface exports
// (concentration HIGH, 2026-08-19). Same-package — callers keep
// splice.spi.{BytesRead,MalformedLine,RawTextObserver}.
package splice.spi

/**
 * Reports the SIZE of one chunk lifted off the socket, before any decoding.
 *
 * Wire bytes, not characters and not events: this is what `sse_bytes_in` on the perf row means, and
 * the reason it is a seam at all is that the reader is the only place those bytes are visible —
 * after decode the chunk boundary is gone.
 */
public fun interface BytesRead {
    public operator fun invoke(count: Int)
}

/**
 * Reports one SSE line the reader could not make sense of, with the offending text.
 *
 * The contract is that it is a REPORT and not a throw: a malformed frame must never crash the
 * stream (the port's literal invariant), so the reader keeps walking and this is where the evidence
 * goes. Production wires the head log.
 */
public fun interface MalformedLine {
    public operator fun invoke(line: String)
}

/**
 * Opt-in observer of the FULL decoded body text as it arrives — every character, not only the
 * `data:`-prefixed lines the event flow yields.
 *
 * Exists for exactly one job: letting a zero-event terminal classify a non-SSE dead-head body (an
 * HTML or JSON login page), which is invisible to a reader that only parses `data:`. Null at every
 * hot-path caller, matching the `perf: TurnPerf? = null` idiom, so the no-per-chunk-allocation
 * invariant holds.
 *
 * The RETURN is the part the shape cannot say: false means "I have seen enough, stop calling me",
 * and the reader drops the reference for the rest of the stream. It is not a success flag, and
 * returning false does not end the stream.
 */
public fun interface RawTextObserver {
    public operator fun invoke(text: CharSequence): Boolean
}
