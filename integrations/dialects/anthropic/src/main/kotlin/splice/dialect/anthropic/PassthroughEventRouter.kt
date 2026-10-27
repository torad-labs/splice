// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: Anthropic-frame SHAPE
// knowledge (which key holds the event type, the content block, the stop_reason, the usage object,
// the error), the only remaining reader of the raw event object in the driver path. Referenced only
// by driveTurn — a strict DAG, no back-reference to the translator.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.sse.WireSink

/** Dispatches one upstream Anthropic SSE frame to its owning collaborators. This translator only
 *  READS the upstream terminal discriminators to drive the WireSink (which has no terminal verbs)
 *  — it is not a second wire emitter, and it names the frames through [UpstreamFrame], never as wire literals. */
internal class PassthroughEventRouter(
    private val blocks: PassthroughBlockRegistry,
    private val terminal: PassthroughTerminalState,
    private val usage: PassthroughUsage,
) {
    private val shape = PassthroughOutputShape()

    /** See [PassthroughOutputShape.endedByModel]. */
    internal val endedByModel: Boolean get() = shape.endedByModel

    internal fun describeOutput(): String = "${shape.describe()} ${usage.describe()}"

    internal suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        sink.withSourceFrame(evt) { wire -> dispatch(evt, wire) }
    }

    private suspend fun dispatch(evt: JsonObject, sink: WireSink) {
        when (UpstreamVocabulary.frame(JsonScalars.strOrEmpty(evt["type"]))) {
            UpstreamFrame.MESSAGE_START ->
                usage.harvestUsage((evt["message"] as? JsonObject)?.get("usage") as? JsonObject)
            UpstreamFrame.CONTENT_BLOCK_START -> {
                shape.openBlock(
                    JsonScalars.int(evt, "index"),
                    JsonScalars.strOrEmpty((evt["content_block"] as? JsonObject)?.get("type")),
                )
                blocks.onBlockStart(evt, sink)
            }
            UpstreamFrame.CONTENT_BLOCK_DELTA -> blocks.onBlockDelta(evt, sink)
            UpstreamFrame.CONTENT_BLOCK_STOP -> {
                shape.closeBlock(JsonScalars.int(evt, "index"))
                blocks.onBlockStop(evt, sink)
            }
            UpstreamFrame.MESSAGE_DELTA -> onMessageDelta(evt)
            UpstreamFrame.MESSAGE_STOP -> terminal.finished = true
            UpstreamFrame.ERROR -> terminal.onError(evt)
            null -> blocks.relayEvent(evt, sink)
        }
    }

    /** stop_reason classification first, then the turn-level usage delta — the order the pre-split
     *  onMessageDelta ran them in. */
    private fun onMessageDelta(evt: JsonObject) {
        val delta = evt["delta"] as? JsonObject
        val reason = JsonScalars.strOrEmpty(delta?.get("stop_reason"))
        terminal.onStopReason(reason, delta)
        shape.onStopReason(reason)
        usage.harvestUsage(evt["usage"] as? JsonObject)
    }
}
