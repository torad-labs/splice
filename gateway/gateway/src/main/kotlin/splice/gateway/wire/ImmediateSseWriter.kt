// NEW: flush-per-frame SSE writer. Was `CoalescingSseWriter` (renamed 2026-07-18, craft review):
// after the lull-bug fix it flushes EVERY frame, so the old name lied and invited a future dev to
// "restore" the exact bug — a frame buffered across an upstream lull (prefill, thinking pause) is
// invisible to the user precisely when responsiveness matters. If batching ever returns it MUST be
// push-based (a timer that flushes a dirty writer during lulls), never wait-for-next-write.
package splice.gateway.wire

/**
 * Writes one SSE frame's bytes to the client socket, unbuffered by us.
 *
 * The RAW half of the pair: it does not flush, so a frame written and not flushed is a frame the
 * user cannot see. That is the lull bug this file's rename exists to warn about, and it is why the
 * write and the flush are two named ports rather than one `emit` — [ImmediateSseWriter] is the only
 * thing entitled to pair them, and it always does.
 */
public fun interface RawSseWrite {
    public operator fun invoke(frame: String)
}

/**
 * Pushes whatever the underlying writer is holding out to the client.
 *
 * Idempotent and cheap by contract: [ImmediateSseWriter.flush] is called again from the head's
 * finally block on abandon and exception paths, after every frame has already flushed itself.
 */
public fun interface RawSseFlush {
    public operator fun invoke()
}

/**
 * Wraps a raw SSE [writeRaw]/[flushRaw] pair. Every frame is written AND flushed immediately.
 * [flush] stays public for the head's finally-block (abandon / exception paths); it is a plain
 * push of the underlying writer and safe to call after every frame already flushed.
 */
public class ImmediateSseWriter(
    private val writeRaw: RawSseWrite,
    private val flushRaw: RawSseFlush,
) {
    public fun write(frame: String) {
        writeRaw(frame)
        flushRaw()
    }

    /** Push the underlying writer once more (harmless when nothing is pending). */
    public fun flush() {
        flushRaw()
    }
}
