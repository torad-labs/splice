// NEW: SseEmitter's construction seam split out of SseEmitter.kt (concentration campaign, HD-24).
// The name TurnDriver actually depends on (it never names the concrete SseEmitter), and the single
// place that assembles the collaborator graph: SseFrameWriter -> MessageStart -> WireBlockWriter
// -> SseEmitter. SseEmitter's constructor stays `internal` — Kotlin `internal` is module-wide, so
// this factory in the same module calls it unchanged.
package splice.head.wire

import splice.core.failure.TurnVoice
import splice.core.failure.WireAnswer
import splice.core.turn.WireFacts
import java.util.concurrent.atomic.AtomicInteger

/** The one construction seam for [SseEmitter] (its constructor stays `internal`) — injected as a
 *  collaborator rather than reached as a static `SseEmitter.create`. */
internal class SseEmitterFactory {
    /**
     * [streaming]'s progress write is where the KEEPALIVE PINGER's own frames go — the heartbeat ping and the
     * status line splice writes on a wire that has gone quiet (SseEmitter.heartbeat / progress).
     * It is a separate port on purpose, and it is the whole of the accounting story: those frames
     * are the proxy's, not the model's, so the head points it at a write that does not count them
     * as content (ClientChannel.timedProgressWrite). Nothing has to recognise a frame after the
     * fact — the path it was written through IS the answer. Defaulted to [write] for the callers
     * with no pinger (the local answer and the compaction replay), which never call either verb.
     *
     * [voice] is the turn's voice, which the emitter's error ending speaks in (failure-handling slice 2).
     */
    public fun create(
        write: FrameWrite,
        model: String,
        usagePayload: UsagePayloadBuilder,
        streaming: StreamWiring = StreamWiring(),
        voice: TurnVoice,
    ): SseEmitter {
        val frames = SseFrameWriter(write, streaming.ending)
        val start = MessageStart(frames, model, streaming.messageId, usagePayload)
        // ONE index sequence, two writers: see WireBlockWriter's nextBlockIndex. The turn's writer
        // ends the pinger's notice at its own block boundaries, so the pinger's wire comes first.
        val indexes = AtomicInteger(0)
        val progress = ProgressWire(streaming.progressTo(write), streaming.retryTo(write), start, indexes)
        val blocks = WireBlockWriter(frames, start, indexes, notice = progress, facts = streaming.facts)
        return SseEmitter(frames, start, blocks, progress, usagePayload, streaming.contentReached, voice)
    }
}

/**
 * What only the streaming path can supply to an emitter. [progressWrite] is the pinger's own port
 * (null = the emitter's [write]). [contentReached] is V4-81's content-reached answer the emitter's
 * failure rule turns on. It DEFAULTS TO TRUE — the INERT reading — because a caller that cannot prove
 * nothing has been written must not be handed a relabel it cannot justify: true means "assume content
 * may have reached the client", and the rule then leaves the type exactly as it found it. Only the
 * streaming path can answer it truthfully, because only that path counts what it writes
 * (ClientChannel.timedClientWrite → CONTENT_FRAMES_OUT); the local answer and the compaction replay
 * take the default and keep the types they always sent.
 */
internal class StreamWiring(
    private val progressWrite: FrameWrite? = null,
    val contentReached: ContentReached = ContentReached { true },
    /** What the client has been shown, kept for the live listing (the turn's writer fills it). */
    val facts: WireFacts? = null,
    private val retryWrite: FrameWrite? = null,
    /** Where the turn's error ending goes while the response may still answer with a status (null: through the
     *  frame write like any frame). Only the streaming path holds its status open, so only it can answer one. */
    val ending: EndingWrite? = null,
    /** The id the emitter's message_start carries: a fresh one per emitter, pinned only by a caller that must know it
     *  before the stream starts. */
    val messageId: String = MessageIds().generateMessageId(),
) {
    /** The port the pinger's frames go through for an emitter that writes to [write]. */
    fun progressTo(write: FrameWrite): FrameWrite = progressWrite ?: write

    /** Retries share progress accounting, but do not choose HTTP success before the upstream answers. */
    fun retryTo(write: FrameWrite): FrameWrite = retryWrite ?: progressTo(write)
}

/**
 * A turn's error ending on a response that may not have committed its status (failure-handling slice 2): the SSE
 * [frame] a stream writes, what the failure is while the status is still splice's to choose ([unsent]), and the
 * error [body] that status carries. The path an ending takes is the answer, so nothing reads a line's words to
 * choose the status: a compaction's and a first message's overflow lines leave out the words a client compacts on
 * (final-design.md section 11), and they keep the 400 every overflow before commit answers with.
 */
internal fun interface EndingWrite {
    suspend operator fun invoke(frame: String, unsent: WireAnswer, body: String)
}
