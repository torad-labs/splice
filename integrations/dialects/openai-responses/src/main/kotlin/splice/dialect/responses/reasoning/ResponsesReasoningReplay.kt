// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the encrypted-envelope
// replay path — the only code that touches ctx.encodeReasoningEnvelope / emitEncryptedReasoning; it
// emits an opaque handle, never text, and is called from onItemDone, not the thinking-delta path.
// 2026-10-23: the reasoning GPT gives in a row reaches Claude Code as one block, written before the next item that
// is not reasoning or at the round's end. Claude Code draws each block as an empty "Thinking…" row.
package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonObject
import splice.core.reasoning.ReasoningReplay
import splice.core.util.JsonScalars
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.StreamTurnContext
import splice.upstream.sse.WireSink

/** onItemDone's replay half: hold the encrypted reasoning for Claude Code (gated) and collect its envelope for
 *  fold/re-anchor replay; the run it holds is shown before what GPT gave next. */
internal class ResponsesReasoningReplay(private val ctx: StreamTurnContext, private val state: ResponsesTurnState) {

    /** The envelopes of the reasoning GPT gave since Claude Code was last shown something that is not reasoning. */
    private val run = mutableListOf<String>()

    fun collect(item: JsonObject) {
        val envelope = ctx.encodeReasoningEnvelope(item) ?: return
        if (shouldEmitReasoning(item)) run += envelope
        if (ctx.reasoningCapture.collectEnvelopes) state.reasoningEnvelopes.add(envelope)
    }

    /** Shows Claude Code the run of reasoning ahead of [item], which GPT gave next, unless it is reasoning too. */
    suspend fun before(item: JsonObject, sink: WireSink) {
        if (JsonScalars.strOrEmpty(item["type"]) != "reasoning") flush(sink)
    }

    /** Shows Claude Code the run of reasoning as one redacted_thinking block, however many items it holds. */
    suspend fun flush(sink: WireSink) {
        if (run.isEmpty()) return
        sink.addRedactedThinking(ReasoningReplay.joinReasoningEnvelopes(run))
        run.clear()
        // CX-09: the redacted block reached the sink too — without this flag an
        // encrypted-reasoning-ONLY turn reads as empty and earns a false empty_model api_error.
        state.emittedThinking = true
    }

    /** Gated encrypted-reasoning EMISSION predicate — kept out of the caller so its condition stays flat. */
    private fun shouldEmitReasoning(item: JsonObject): Boolean =
        ctx.emitEncryptedReasoning.v && !ctx.compact &&
            JsonScalars.strOrEmpty(item["type"]) == "reasoning" &&
            JsonScalars.strOrEmpty(item["encrypted_content"]).isNotEmpty()
}
