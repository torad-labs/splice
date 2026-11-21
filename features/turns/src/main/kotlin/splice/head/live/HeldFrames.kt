// NEW 2026-11-19 (reply-stream): the frames a turn in flight has written to its client, held for a follower that joins
// mid-reply. References only: nothing is copied or parsed while nobody follows. Charged to the heap budget the way
// FrameRecording charges its frames; when the budget refuses, the turn stops holding and drops what it held, and a
// later joiner gets the turn and the live deltas from there, never a wrong snapshot.
package splice.head.live

import splice.core.memory.HeapCategory
import splice.core.memory.HeapJson
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations

// why: the list's reference and growth slack, as FrameRecording counts an entry.
private const val HELD_FRAME_ENTRY_BYTES = 64L

/** Not thread-safe: its turn's session lock guards it. */
internal class HeldFrames(heap: HeapReservations) {
    private val frames = ArrayList<String>()
    private var lease: HeapLease? = heap.reserve(0L, HeapCategory.REPLAY)?.also { HeapOwners.keep(frames, it) }

    /** Every frame held so far, in order; null once the budget refused one (or [drop]), so no snapshot is whole. */
    fun all(): List<String>? = frames.takeIf { lease != null }

    fun add(frame: String) {
        val charge = lease ?: return
        if (charge.resize(charge.bytes + HeapJson.text(frame) + HELD_FRAME_ENTRY_BYTES)) {
            frames += frame
        } else {
            drop()
        }
    }

    /** Lets go of every frame and the charge: the turn has ended, or the budget refused. */
    fun drop() {
        frames.clear()
        lease?.close()
        lease = null
    }
}
