// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: Anthropic-frame SHAPE
// knowledge (which key holds the event type, the content block, the stop_reason, the usage object,
// the error), the only remaining reader of the raw event object in the driver path. Referenced only
// by driveTurn — a strict DAG, no back-reference to the translator.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.spi.WireSink

/** Dispatches one upstream Anthropic SSE frame to its owning collaborators. This translator only
 *  READS the upstream terminal discriminators to drive the WireSink (which has no terminal verbs)
 *  — it is not a second wire emitter, hence the localized L3 wall exceptions below. */
internal class PassthroughEventRouter(
    private val blocks: PassthroughBlockRegistry,
    private val terminal: PassthroughTerminalState,
    private val usage: PassthroughUsage,
) {

    internal suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        when (JsonScalars.strOrEmpty(evt["type"])) {
            "message_start" -> usage.harvestUsage((evt["message"] as? JsonObject)?.get("usage") as? JsonObject)
            "content_block_start" -> blocks.onBlockStart(evt, sink)
            "content_block_delta" -> blocks.onBlockDelta(evt, sink)
            "content_block_stop" -> blocks.onBlockStop(evt, sink)
            // ast-grep-ignore: kt-l3-sole-wire-terminals — reading upstream discriminator, not emitting
            "message_delta" -> onMessageDelta(evt)
            // ast-grep-ignore: kt-l3-sole-wire-terminals — reading upstream discriminator, not emitting
            "message_stop" -> terminal.finished = true
            "error" -> onError(evt)
            else -> Unit // ping / unknown events are ignored
        }
    }

    /** stop_reason classification first, then the turn-level usage delta — the order the pre-split
     *  onMessageDelta ran them in. */
    private fun onMessageDelta(evt: JsonObject) {
        terminal.onStopReason(JsonScalars.strOrEmpty((evt["delta"] as? JsonObject)?.get("stop_reason")))
        usage.harvestUsage(evt["usage"] as? JsonObject)
    }

    private fun onError(evt: JsonObject) {
        val err = evt["error"] as? JsonObject
        terminal.onError(JsonScalars.strOrEmpty(err?.get("type")), JsonScalars.strOrEmpty(err?.get("message")))
    }
}
