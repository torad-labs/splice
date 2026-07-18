// NEW: flush-per-frame SSE writer. Was `CoalescingSseWriter` (renamed 2026-07-18, craft review):
// after the lull-bug fix it flushes EVERY frame, so the old name lied and invited a future dev to
// "restore" the exact bug — a frame buffered across an upstream lull (prefill, thinking pause) is
// invisible to the user precisely when responsiveness matters. If batching ever returns it MUST be
// push-based (a timer that flushes a dirty writer during lulls), never wait-for-next-write.
package splice.gateway.wire

/**
 * Wraps a raw SSE [writeRaw]/[flushRaw] pair. Every frame is written AND flushed immediately.
 * [flush] stays public for the head's finally-block (abandon / exception paths); it is a plain
 * push of the underlying writer and safe to call after every frame already flushed.
 */
public class ImmediateSseWriter(
    private val writeRaw: (String) -> Unit,
    private val flushRaw: () -> Unit,
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
