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
//
// Failure-handling slice 2 (2026-10-26): every line written here is a SpliceLine, so only a turn's voice can say one,
// and the pair puts the line break between two lines of one block. It also takes the line that opens a recovery
// episode, from the turn's own coroutine under the same lock, and shows one per episode (final-design.md section 8).
package splice.head.wire

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.failure.SpliceLine
import splice.core.index.WireBlockIndex
import splice.core.turn.SpliceNotice
import java.util.concurrent.atomic.AtomicInteger

/** The pinger's frame writer and block writer, and the one notice block they may hold open. */
internal class ProgressWire(
    private val ordinaryWrite: FrameWrite,
    private val retryWrite: FrameWrite,
    start: MessageStart,
    indexes: AtomicInteger,
) {
    // All access, including the temporary retry route, is under lock.
    private var activeWrite = ordinaryWrite
    private val frames = SseFrameWriter(write = { activeWrite(it) })
    private val blocks = WireBlockWriter(frames, start, indexes)

    /** Guards both writers and [notice]. SseEmitter holds it to re-read its seal before a pinger
     *  write; the turn takes it to end the notice. */
    val lock = Mutex()

    // The open notice block, if any. Volatile so the turn's writer reads it without the lock on its
    // hot path, where nearly always no notice is open.
    @Volatile private var notice: WireBlockIndex? = null

    // Whether a recovery episode is open: its line was shown and no model content has reached the client since. Read
    // and written under [lock]. It ends with the notice its line went into, which the turn's writer ends before its
    // next block of model content and the ending ends with the turn (final-design.md section 8, rule 5).
    private var episode = false

    /** One `ping` event. The caller holds [lock]. */
    suspend fun pingLocked() {
        frames.writeVerbatim(PING_FRAME)
    }

    /** Append the status line [line] composes, opening a notice block when none is open. The caller
     *  holds [lock], past every guard, because composing a line consumes the caller's ticker state
     *  (TurnTerminal.progress); a null line writes nothing. */
    suspend fun writeNoticeLocked(line: ProgressLine) {
        val said = line(notice == null) ?: return
        appendLocked(separated(said), commits = true)
    }

    /** Append the line that opens a recovery episode, unless one is open: a failure that follows another before model
     *  content reaches the client again tells the person nothing new (final-design.md section 8, rules 3 and 4). The
     *  caller holds [lock]. The line commits the response as it is written (PendingSse, slice 5). */
    suspend fun writeEpisodeLocked(line: SpliceLine) {
        if (episode) return
        episode = true
        appendLocked(separated(line), commits = false)
    }

    /** A hold's line (failure-handling slice 5) is a finished notice at once: the turn may wait for hours, and the client
     *  shows a notice when its block closes. The episode stays open, so a hold entered again after a probe that did not
     *  lift it says nothing new. The caller holds [lock]. */
    suspend fun closeEpisodeLocked() {
        notice?.let { index ->
            blocks.signatureDelta(index, SpliceNotice.SIGNATURE)
            blocks.closeBlock(index)
        }
        notice = null
    }

    /** [line] as the block takes it: on its own when it opens the block, after a line break when another is there. */
    private fun separated(line: SpliceLine): String = if (notice == null) line.text else "\n${line.text}"

    private suspend fun appendLocked(text: String, commits: Boolean) {
        activeWrite = if (commits) ordinaryWrite else retryWrite
        try {
            val index = notice ?: blocks.openThinking().also { notice = it }
            blocks.thinkingDelta(index, text)
        } finally {
            activeWrite = ordinaryWrite
        }
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
            episode = false
        }
    }
}
