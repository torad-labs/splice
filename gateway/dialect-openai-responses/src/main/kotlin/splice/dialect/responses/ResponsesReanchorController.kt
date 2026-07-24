// NEW: mid-stream re-anchoring (eli design 2026-07-24) — the proxy-side answer to codex-rs's
// whole-stream retry (responses_retry.rs). A round that fails RETRYABLY after frames were already
// forwarded is not surfaced to the client: the wire sits at a clean block boundary (the translator
// closeAlls before its terminal decision), so the turn re-POSTs a continuation carrying the
// accumulated partial output and APPENDS the remainder as new blocks. Replaying sent frames is
// impossible for a proxy (codex-rs overwrites its own terminal render; splice cannot un-send
// bytes) — but append needs no replay, and Claude Code commits nothing before message_stop.
// One honest terminal ends the whole turn (L3); every ineligible case falls back to the error.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.spi.ReanchorController
import splice.spi.ReanchorRound

public class ResponsesReanchorController(
    private val decodeReasoningEnvelope: (String) -> JsonObject?,
    private val maxContinuations: Int = DEFAULT_MAX_CONTINUATIONS,
) : ReanchorController {

    override fun continuationForFailure(round: ReanchorRound): JsonObject? {
        val partial = round.failure.partial ?: return null
        if (!eligible(round, partial)) return null
        val items = buildList {
            partial.reasoningEnvelopes.mapNotNullTo(this) { decodeReasoningEnvelope(it) }
            if (partial.bodyText.isNotEmpty()) add(assistantText(partial.bodyText))
            add(reanchorMarker())
        }
        return continuationRequest(round.requestBody, items)
    }

    // Tool blocks end eligibility both ways: an OPEN tear committed partial args JSON to the
    // wire (corrupt block, nothing to splice onto), and a COMMITTED tool_use means the
    // continuation input would carry a function_call without its function_call_output (a 400)
    // while a re-emitted call risks double-dispatch. Both fall back to the honest error — the
    // brownout class this exists for dies in the long reasoning/text phase, not tool JSON.
    private fun eligible(round: ReanchorRound, partial: TurnOutcome.PartialRound): Boolean = when {
        round.attempt >= maxContinuations -> false
        round.failure.type !in RETRYABLE -> false
        partial.toolTearOpen || partial.hasToolUse -> false
        else -> hasSalvage(partial)
    }

    /** Re-anchoring exists to SALVAGE forwarded work — and only content the continuation can
     *  actually REPLAY counts: prose (rides as an assistant item) or encrypted reasoning
     *  envelopes. thinkingText alone does NOT qualify (code-review 2026-07-24): it cannot seed
     *  the continuation, so a mid-reasoning-delta death with no completed reasoning item would
     *  continue with only the marker — an incoherent restart that burns budget. Those surface
     *  honestly instead. Nothing-to-salvage rounds likewise surface (HeadServerCapacityTest). */
    private fun hasSalvage(partial: TurnOutcome.PartialRound): Boolean {
        if (partial.bodyText.isNotEmpty()) return true
        return partial.reasoningEnvelopes.isNotEmpty()
    }

    /** The partial prose the client already saw, replayed as context so the model resumes it. */
    private fun assistantText(text: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", text)
    }

    // phase:commentary keeps the marker out of the visible transcript (the fold marker trick) and
    // satisfies the Responses "reasoning item needs a following item" constraint.
    private fun reanchorMarker(): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("phase", "commentary")
        put("content", MARKER_TEXT)
    }

    public companion object {
        public const val DEFAULT_MAX_CONTINUATIONS: Int = 2
        public const val MARKER_TEXT: String =
            "Your previous stream was interrupted mid-answer. Continue EXACTLY where the text " +
                "above stops — do not repeat or restate anything already written, and do not " +
                "restate reasoning you have already given."
        private val RETRYABLE = setOf(ErrorType.OVERLOADED, ErrorType.API_ERROR)
    }
}
