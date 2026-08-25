// NEW: the gateway-side ANSWER to a Responses tool_search call. The tool declares
// execution:"client" (codex core/src/tools/handlers/tool_search_spec.rs:68-76), so the model emits
// a distinct tool_search_call item (protocol/src/models.rs:878-892) and waits for a distinct
// tool_search_output item (:695-701) — NOT a function_call/function_call_output pair, and NOT
// something Claude Code can be asked to run. splice answers it locally and re-POSTs inside the same
// client turn, through the SAME append-only continuationRequest fold/re-anchor already use.
// Invariants:
//   - the search call NEVER becomes a client-visible block: no WireSink verb is reachable from here
//     and the controller returns a request BODY, so L3 is untouched by construction;
//   - APPEND-ONLY: only `input` grows (closed-DTO continuationRequest), so the prompt-cache prefix
//     and every other request field survive the extra round;
//   - THE LOOP CANNOT WEDGE: the final permitted round answers with the ENTIRE deferred set, so a
//     further search is pointless; capability at the cap is exactly today's full surface;
//   - PER-TURN ONLY: this object holds the turn's deferred inventory and nothing else. There is no
//     cache, no TTL, no keyed store — a restart cannot lose anything that exists.
// Ranking lives in ToolSearchIndex.kt; the output shape lives in ToolSearchOutput.kt
// (concentration, 2026-08-19).
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.ToolSearchCall
import splice.core.wire.ToolDefinition
import splice.spi.ToolSearchController
import splice.spi.ToolSearchRound

/** Per-TURN answering policy. Holds the turn's deferred inventory and nothing else; allocated by
 *  the request builder, garbage-collected with the turn. No cross-turn state exists anywhere. */
internal class ResponsesToolSearchController(
    private val index: ToolSearchIndex,
    private val policy: ToolDeferralPolicy,
    private val emitStrict: Boolean,
    private val forceStrictFalse: Boolean,
    private val decodeReasoningEnvelope: ReasoningEnvelopeDecoder,
) : ToolSearchController {

    private val continuation = ResponsesContinuation()
    private val output = ToolSearchOutput()

    override fun continuationForSearch(round: ToolSearchRound): JsonObject? {
        if (stopSearching(round)) return null
        val exhaustive = round.roundIndex == policy.searchRounds - 1
        val items = buildList {
            // this round's reasoning items, ONLY when non-empty (a dangling reasoning item with no
            // following item is a 400 — the same idiom ResponsesFoldController.continuation uses).
            addAll(round.outcome.reasoningEnvelopes.mapNotNull(decodeReasoningEnvelope::invoke))
            // The round's own prose, already on the client's wire — replay it as context so the
            // model does not re-say it (ResponsesReanchorController.assistantText's sibling rule).
            // Gated on emittedText (not just bodyText.isNotEmpty()): on FoldRunner's buffered path
            // the CALLER strips both to "" / false before this outcome ever reaches here, so a
            // buffered-and-discarded round can never leak never-forwarded prose (review 2026-07-24).
            if (round.outcome.emittedText && round.outcome.bodyText.isNotEmpty()) {
                add(assistantText(round.outcome.bodyText))
            }
            answeredOnce(round.outcome.toolSearches).forEach { call ->
                add(call.raw) // verbatim — the backend's own shape, never a re-authored guess
                add(
                    output.toolSearchOutputItem(
                        call.callId.v,
                        answerFor(call, exhaustive),
                        emitStrict,
                        forceStrictFalse,
                    ),
                )
            }
        }
        return continuation.continuationRequest(round.requestBody, items)
    }

    /** The round's prose the client already saw, replayed as context — mirrors
     *  ResponsesReanchorController.assistantText (duplicated, not shared: a 2-line JSON builder,
     *  and every wire literal in this file is already private-per-file). */
    private fun assistantText(text: String): JsonObject = buildJsonObject {
        put(FIELD_ROLE, ROLE_ASSISTANT)
        put(FIELD_CONTENT, text)
    }

    // Stop conditions, each a plain early return via a flat when — never a compound boolean.
    private fun stopSearching(round: ToolSearchRound): Boolean = when {
        round.outcome.hasToolUse -> true // a real tool_use already committed to the client's wire
        round.outcome.toolSearches.isEmpty() -> true // nothing to answer
        else -> round.roundIndex >= policy.searchRounds // hard stop (unreachable in practice)
    }

    /** Dedup by call_id, first wins — a round can (in principle) carry the same id twice. */
    private fun answeredOnce(calls: List<ToolSearchCall>): List<ToolSearchCall> {
        val seen = HashSet<String>(calls.size)
        return calls.filter { seen.add(it.callId.v) }
    }

    // The final permitted round hands over the ENTIRE deferred set (the loop-can't-wedge law);
    // an unrankable blank query is answered the same way rather than starving the model with [].
    private fun answerFor(call: ToolSearchCall, exhaustive: Boolean): List<ToolDefinition> = when {
        exhaustive -> index.all()
        call.query.isBlank() -> index.all()
        else -> index.search(call.query, clampedLimit(call.limit))
    }

    private fun clampedLimit(requested: Int?): Int = (requested ?: policy.searchLimit).coerceIn(1, policy.searchLimit)
}

private const val FIELD_ROLE = "role"
private const val FIELD_CONTENT = "content"
private const val ROLE_ASSISTANT = "assistant"
