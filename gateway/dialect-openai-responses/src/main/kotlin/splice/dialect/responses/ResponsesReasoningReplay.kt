// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the encrypted-envelope
// replay path — the only code that touches ctx.encodeReasoningEnvelope / emitEncryptedReasoning; it
// emits an opaque handle, never text, and is called from onItemDone, not the thinking-delta path.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.spi.WireSink

/** onItemDone's replay half: emit the encrypted reasoning IN POSITION (gated) and collect its
 *  envelope for fold/re-anchor replay. */
internal class ResponsesReasoningReplay(private val ctx: StreamTurnContext, private val state: ResponsesTurnState) {

    suspend fun emitReplayedReasoning(item: JsonObject, sink: WireSink) {
        val envelope = ctx.encodeReasoningEnvelope(item) ?: return
        if (shouldEmitReasoning(item)) {
            sink.addRedactedThinking(envelope)
            // CX-09: the redacted block reached the sink too — without this flag an
            // encrypted-reasoning-ONLY turn reads as empty and earns a false empty_model api_error.
            state.emittedThinking = true
        }
        if (ctx.collectReasoningEnvelopes) state.reasoningEnvelopes.add(envelope)
    }

    /** Gated encrypted-reasoning EMISSION predicate — kept out of the caller so its condition stays flat. */
    private fun shouldEmitReasoning(item: JsonObject): Boolean =
        ctx.emitEncryptedReasoning.v && !ctx.compact &&
            JsonScalars.strOrEmpty(item["type"]) == "reasoning" &&
            JsonScalars.strOrEmpty(item["encrypted_content"]).isNotEmpty()
}
