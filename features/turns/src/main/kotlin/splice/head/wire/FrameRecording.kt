// NEW: an append-only record of one turn's client frames that can be FOLLOWED while it is still
// being written (2026-09-05). What a compaction leaves behind when its client hangs up: the turn
// runs on detached (ClientChannel), every frame lands here, and the client's byte-identical retry is
// served from it — the frames so far at once, the rest as they arrive, done at the terminal.
// Whether the recording is a WHOLE answer is the terminal's fact (TurnTerminal.endedCleanly), not
// something read off the frames: no terminal literal lives outside SseEmitter (L3). The drive
// hands that verdict in at complete(), and a follower reads it back from follow() — a follower
// already on a recording when its drive fails is the one retry CompactionReplay.finish cannot
// turn away (review of PR 137), so the verdict has to reach it through the recording itself.
package splice.head.wire

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.util.Cancellables
import splice.upstream.memory.JvmHeap
import java.util.UUID

// why: the array-list's reference and growth slack; escaped strings own their metadata separately.
private const val FRAME_ENTRY_BYTES = 64L

/** [generation] names this answer for as long as anything holds it: the durable copy is kept under it, so a delivery spends exactly
 *  its own copy and never a newer one stored under the same key. A recording read back from disk takes the stored generation. */
internal class FrameRecording(
    private val heap: HeapReservations = JvmHeap.budget,
    val generation: String = UUID.randomUUID().toString(),
    /** Runs as completion begins, inside the torn-on-failure guard: the seam a test fails completion through. */
    private val beforeComplete: Runnable = Runnable {},
) {

    private data class Progress(val frames: Int, val complete: Boolean, val whole: Boolean = false)

    private val lock = Any()
    private val frames = ArrayList<String>()
    private val heapLease = HeapOwners.charge(frames, heap, 0L)
    private val progress = MutableStateFlow(Progress(0, false))

    // Allocated before recording starts: a failed completion must still wake attached followers.
    private val torn = Progress(0, true)

    val isComplete: Boolean get() = progress.value.complete

    /** The drive's verdict: true when its terminal ended cleanly (meaningless before complete). */
    val isWhole: Boolean get() = progress.value.whole
    val size: Int get() = synchronized(lock) { frames.size }

    /** Every frame recorded so far, in order (V4-216: what a finished recording is stored as). */
    fun frames(): List<String> = synchronized(lock) {
        val copy = heap.reserve(frames.size * FRAME_ENTRY_BYTES) ?: throw HeapCapacityException()
        var kept = false
        try {
            frames.toList().also {
                HeapOwners.keep(it, copy)
                kept = true
            }
        } finally {
            if (!kept) copy.close()
        }
    }

    fun append(frame: String) {
        val count = synchronized(lock) {
            // The copied string and its temporary character array coexist before the handoff.
            val peak = heap.reserve(HeapJson.text(frame) + frame.length * 2L) ?: throw HeapCapacityException()
            peak.use {
                if (!heapLease.resize(heapLease.bytes + FRAME_ENTRY_BYTES)) throw HeapCapacityException()
                // Fresh ownership also works for empty, static and interned caller strings.
                val owned = String(frame.toCharArray())
                HeapOwners.keep(owned, peak.split(HeapJson.text(owned)))
                frames.add(owned)
                frames.size
            }
        }
        progress.update { it.copy(frames = count) }
    }

    /** No more frames will come. Followers drain what is recorded and return [whole], the drive's
     *  verdict on its own terminal. */
    fun complete(whole: Boolean) {
        var completed = false
        Cancellables.withCleanup({ if (!completed) progress.value = torn }) {
            beforeComplete.run()
            progress.update { it.copy(complete = true, whole = whole) }
            completed = true
        }
    }

    /** Deliver every frame recorded so far and every one still to come; returns once complete,
     *  with the verdict handed to [complete]: false means the frames are not a whole answer. */
    suspend fun follow(write: FrameWrite): Boolean {
        var seen = 0
        while (true) {
            val now = progress.value
            val count = size
            if (count > seen) {
                while (seen < count) {
                    val frame = synchronized(lock) { frames[seen] }
                    write(frame)
                    seen++
                }
            }
            if (now.complete && size == seen) return now.whole
            progress.first { it.frames > seen || it.complete }
        }
    }
}
