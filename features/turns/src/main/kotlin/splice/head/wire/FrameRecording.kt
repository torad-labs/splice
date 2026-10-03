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
import splice.core.util.Cancellables

internal class FrameRecording {

    private data class Progress(val frames: Int, val complete: Boolean, val whole: Boolean = false)

    private val lock = Any()
    private val frames = ArrayList<String>()
    private val progress = MutableStateFlow(Progress(0, false))

    // Allocated before recording starts: a failed completion must still wake attached followers.
    private val torn = Progress(0, true)

    val isComplete: Boolean get() = progress.value.complete

    /** The drive's verdict: true when its terminal ended cleanly (meaningless before complete). */
    val isWhole: Boolean get() = progress.value.whole
    val size: Int get() = synchronized(lock) { frames.size }

    /** Every frame recorded so far, in order (V4-216: what a finished recording is stored as). */
    fun frames(): List<String> = synchronized(lock) { frames.toList() }

    fun append(frame: String) {
        val count = synchronized(lock) {
            frames.add(frame)
            frames.size
        }
        progress.update { it.copy(frames = count) }
    }

    /** No more frames will come. Followers drain what is recorded and return [whole], the drive's
     *  verdict on its own terminal. */
    fun complete(whole: Boolean) {
        var completed = false
        Cancellables.withCleanup({ if (!completed) progress.value = torn }) {
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
                val batch = synchronized(lock) { frames.subList(seen, count).toList() }
                batch.forEach { write(it) }
                seen = count
            }
            if (now.complete && size == seen) return now.whole
            progress.first { it.frames > seen || it.complete }
        }
    }
}
