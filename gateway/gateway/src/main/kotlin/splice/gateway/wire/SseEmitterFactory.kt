// NEW: SseEmitter's construction seam split out of SseEmitter.kt (concentration campaign, HD-24).
// The name TurnDriver actually depends on (it never names the concrete SseEmitter), and the single
// place that assembles the collaborator graph: SseFrameWriter -> MessageStart -> WireBlockWriter
// -> SseEmitter. SseEmitter's constructor stays `internal` — Kotlin `internal` is module-wide, so
// this factory in the same module calls it unchanged.
package splice.gateway.wire

/** The one construction seam for [SseEmitter] (its constructor stays `internal`) — injected as a
 *  collaborator rather than reached as a static `SseEmitter.create`. */
public class SseEmitterFactory {
    public fun create(
        write: FrameWrite,
        model: String,
        usagePayload: UsagePayloadBuilder,
        messageId: String = MessageIds().generateMessageId(),
    ): SseEmitter {
        val frames = SseFrameWriter(write)
        val start = MessageStart(frames, model, messageId, usagePayload)
        val blocks = WireBlockWriter(frames, start)
        return SseEmitter(frames, start, blocks, usagePayload)
    }
}
