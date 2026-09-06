// NEW: an append-only record of one turn's client frames that can be FOLLOWED while it is still
// being written (2026-09-05). What a compaction leaves behind when its client hangs up: the turn
// runs on detached (ClientChannel), every frame lands here, and the client's byte-identical retry is
// served from it — the frames so far at once, the rest as they arrive, done at the terminal.
// Whether the recording is a WHOLE answer is the terminal's fact (TurnTerminal.endedCleanly), not
// something read off the frames: no terminal literal lives outside SseEmitter (L3).
package splice.gateway.wire

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

internal class FrameRecording {

    private data class Progress(val frames: Int, val complete: Boolean)

    private val lock = Any()
    private val frames = ArrayList<String>()
    private val progress = MutableStateFlow(Progress(0, false))

    val isComplete: Boolean get() = progress.value.complete
    val size: Int get() = progress.value.frames

    fun append(frame: String) {
        val count = synchronized(lock) {
            frames.add(frame)
            frames.size
        }
        progress.update { it.copy(frames = count) }
    }

    /** No more frames will come. Followers drain what is recorded and return. */
    fun complete() {
        progress.update { it.copy(complete = true) }
    }

    /** Deliver every frame recorded so far and every one still to come; returns once complete. */
    suspend fun follow(write: FrameWrite) {
        var seen = 0
        while (true) {
            val now = progress.value
            if (now.frames > seen) {
                val batch = synchronized(lock) { frames.subList(seen, now.frames).toList() }
                batch.forEach { write(it) }
                seen = now.frames
            }
            if (now.complete && now.frames == seen) return
            progress.first { it.frames > seen || it.complete }
        }
    }
}
