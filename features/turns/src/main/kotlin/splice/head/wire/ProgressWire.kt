// NEW: the keepalive pinger's own write surface (2026-09-06), so the pinger and the turn never
// share mutable wire state. Both of the pinger's frames — the heartbeat ping and the status line
// splice writes on a quiet wire — are assembled and written through THIS pair, never through the
// turn's own [SseFrameWriter]/[WireBlockWriter].
//
// It exists because the pinger runs on its own coroutine and the turn's writers are single-writer
// by construction: SseFrameWriter reuses ONE StringBuilder across every frame ("never escapes the
// writer; not concurrent"), so a heartbeat racing a model delta through it would interleave two
// frames into one buffer and put a corrupt event on the wire. A second writer costs one
// StringBuilder per turn and removes the race rather than narrowing it. Only the block-index
// sequence is shared (WireBlockWriter.nextBlockIndex, atomic), so the two writers can never mint
// the same index; the hot delta path stayed exactly as single-writer as it was.
//
// This pair is NOT itself single-writer: the pinger writes the status line, and the TURN ends the
// notice block before each of its own block boundaries and at its ending. Every touch of the pair
// and of the open notice goes through [ProgressWire.lock].
//
// V4-451 (2026-10-01): the notice block used to close only at the ending. Claude Code commits blocks
// in content_block_stop order, so "no answer from the model yet" was drawn BELOW the answer it
// preceded (operator screenshot, Oct 1; trace: first notice at 30 s, first model delta at 103 s).
// The turn's writer now ends the notice before it opens or closes any block of its own.
package splice.head.wire

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.index.WireBlockIndex
import splice.core.turn.SpliceNotice

/** The pinger's frame writer and block writer, and the one notice block they may hold open. */
internal class ProgressWire(
    private val frames: SseFrameWriter,
    private val blocks: WireBlockWriter,
) {
    /** Guards both writers and [notice]. SseEmitter holds it to re-read its seal before a pinger
     *  write; the turn takes it to end the notice. */
    val lock = Mutex()

    // The open notice block, if any. Volatile so the turn's writer reads it without the lock on its
    // hot path, where nearly always no notice is open.
    @Volatile private var notice: WireBlockIndex? = null

    /** One `ping` event. The caller holds [lock]. */
    suspend fun pingLocked() {
        frames.writeVerbatim(PING_FRAME)
    }

    /** Append the status line [line] composes, opening a notice block when none is open. The caller
     *  holds [lock], past every guard, because composing a line consumes the caller's ticker state
     *  (TurnTerminal.progress); a null line writes nothing. */
    suspend fun writeNoticeLocked(line: ProgressLine) {
        val text = line(notice == null) ?: return
        val index = notice ?: blocks.openThinking().also { notice = it }
        blocks.thinkingDelta(index, text)
    }

    /** The turn writer's form of [endNotice], run at each of its block boundaries: one volatile read
     *  when no notice is open, which is nearly always. A notice still being opened at that instant is
     *  not seen, and is ended at the turn's next boundary or its ending instead. */
    suspend fun endNoticeAtBoundary() {
        if (notice != null) endNotice()
    }

    /** End the open notice, if any: splice's own signature after its last delta (Claude Code stores
     *  it with the replayed block and upstream scrubbers discard it exactly), then the stop. Always
     *  takes the lock, so a notice line still in flight is waited for, never stepped over: the ending
     *  relies on that. The next quiet stretch opens a fresh block. */
    suspend fun endNotice() {
        lock.withLock {
            notice?.let { index ->
                blocks.signatureDelta(index, SpliceNotice.SIGNATURE)
                blocks.closeBlock(index)
            }
            notice = null
        }
    }
}
