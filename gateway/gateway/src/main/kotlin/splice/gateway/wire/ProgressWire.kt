// NEW (2026-09-06): the keepalive pinger's own write surface, so the pinger and the turn never
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
// This pair is NOT itself single-writer: the pinger appends the status line and the TURN closes its
// block at the ending, so both of these two writers' own mutable state is guarded by
// SseEmitter.progressMutex. Every touch of this pair goes through there.
package splice.gateway.wire

/** The pinger's frame writer and block writer, held together because they are one seam. */
internal data class ProgressWire(
    val frames: SseFrameWriter,
    val blocks: WireBlockWriter,
)
