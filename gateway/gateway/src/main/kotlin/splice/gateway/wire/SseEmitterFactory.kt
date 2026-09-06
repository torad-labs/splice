// NEW: SseEmitter's construction seam split out of SseEmitter.kt (concentration campaign, HD-24).
// The name TurnDriver actually depends on (it never names the concrete SseEmitter), and the single
// place that assembles the collaborator graph: SseFrameWriter -> MessageStart -> WireBlockWriter
// -> SseEmitter. SseEmitter's constructor stays `internal` — Kotlin `internal` is module-wide, so
// this factory in the same module calls it unchanged.
package splice.gateway.wire

import java.util.concurrent.atomic.AtomicInteger

/** The one construction seam for [SseEmitter] (its constructor stays `internal`) — injected as a
 *  collaborator rather than reached as a static `SseEmitter.create`. */
public class SseEmitterFactory {
    /**
     * [progressWrite] is where the KEEPALIVE PINGER's own frames go — the heartbeat ping and the
     * status line splice writes on a wire that has gone quiet (SseEmitter.heartbeat / progress).
     * It is a separate port on purpose, and it is the whole of the accounting story: those frames
     * are the proxy's, not the model's, so the head points it at a write that does not count them
     * as content (ClientChannel.timedProgressWrite). Nothing has to recognise a frame after the
     * fact — the path it was written through IS the answer. Defaulted to [write] for the callers
     * with no pinger (the local answer and the compaction replay), which never call either verb.
     */
    public fun create(
        write: FrameWrite,
        model: String,
        usagePayload: UsagePayloadBuilder,
        messageId: String = MessageIds().generateMessageId(),
        progressWrite: FrameWrite = write,
    ): SseEmitter {
        val frames = SseFrameWriter(write)
        val start = MessageStart(frames, model, messageId, usagePayload)
        // ONE index sequence, two writers: see WireBlockWriter's nextBlockIndex.
        val indexes = AtomicInteger(0)
        val blocks = WireBlockWriter(frames, start, indexes)
        val progressFrames = SseFrameWriter(progressWrite)
        val progress = ProgressWire(progressFrames, WireBlockWriter(progressFrames, start, indexes))
        return SseEmitter(frames, start, blocks, progress, usagePayload)
    }
}
