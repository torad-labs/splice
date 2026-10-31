// NEW: reasoning-continuation folding for the codex Responses path. A round whose reasoning its provider cut short
// (codex's 518n-2, which CodexReplies reads as a ReasoningCut) is continued by REPLAYING the round's
// reasoning.encrypted_content back to the server with a phase:commentary marker, so the model resumes from the cutoff
// instead of finalizing (the mechanism CodexCont / codexcomp use). encrypted_content is opaque — replayed, never read
// (the server holds the keys). The envelope round-trip reuses core/reasoning/Replay.kt (encode on the stream side into
// TurnOutcome.Success.reasoningEnvelopes, decode HERE back to Responses reasoning input items) — the codex-rs codec is
// never re-authored. Failure-handling slice 4: this file only builds. Whether a round was cut is its provider's reading
// and whether it is continued is RetryEngine's, on the turn's one ledger, so the four fold knobs are gone.
package splice.dialect.responses.stream

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import splice.dialect.responses.reasoning.ReasoningEnvelopeDecoder
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.request.ResponsesCallPairing
import splice.dialect.responses.request.ResponsesRequest
import splice.dialect.responses.request.responsesRequestJson
import splice.upstream.FoldPolicy
import splice.upstream.FoldRound

/** What a provider whose rounds are cut declares for the Responses builder: the upstream [models] whose turns run
 *  round by round so a cut round can be continued, and the [markerText] of the hidden item that asks the model to go
 *  on. Codex declares both from CodexReplies; a provider that declares none passes every turn straight through. */
public data class FoldConfig(
    val models: Set<String>,
    val markerText: String,
)

/**
 * The continuation-request builder for a round its provider cut short: this round's request, its encrypted reasoning
 * replayed, and the marker. A round with no encrypted reasoning to replay cannot be continued, so it answers null and
 * the head emits the round's own answer.
 */
internal class ResponsesFoldPolicy(
    private val config: FoldConfig,
    private val decodeReasoningEnvelope: ReasoningEnvelopeDecoder,
) : FoldPolicy {

    private val continuation = ResponsesContinuation()

    override fun continuation(round: FoldRound): JsonObject? {
        val replay = round.outcome.handoffs.reasoningEnvelopes.mapNotNull { decodeReasoningEnvelope(it) }
        return if (replay.isEmpty()) null else continuationBody(round.requestBody, replay)
    }

    private fun continuationBody(previous: JsonObject, replayItems: List<JsonObject>): JsonObject =
        continuation.continuationRequest(previous, replayItems + listOf(continuationMarker()))

    // A hidden phase:commentary assistant message — the codex-rs / CodexCont / codexcomp nudge.
    // It ALSO satisfies the Responses "reasoning item needs a following item" constraint.
    // V4-342: [ResponsesAssistantText]'s item, the one author of an assistant item; the same bytes.
    private fun continuationMarker(): JsonObject =
        ResponsesAssistantText.item(config.markerText, AssistantPhase.COMMENTARY)
}

/**
 * DTO-faithful continuation shared by fold, re-anchor and tool-search: decode the prior request,
 * extend ONLY its `input` with [continuationRequest]'s extra items, re-encode through the same
 * closed serializer. No request FIELD is added — the #924 closed DTO is untouched, so byte-identity
 * off every path is trivial.
 *
 * A type rather than the file-level function it used to be (Kotlin main sources carry no top-level
 * functions); the member keeps its old name and argument list, so each of the three call sites only
 * gained a receiver.
 */
internal class ResponsesContinuation {
    private val pairing = ResponsesCallPairing()

    /** The summary request RIDES ALONG on continuations by design (operator call 2026-07-26: "I
     *  want to always see the reasoning, detailed"): the duplicated sections that the re-request
     *  provokes are suppressed turn-scoped in the stream translator (SharedSummaryParts) — exact
     *  repeats of a section already emitted this turn never reach the client; every genuinely-new
     *  section does. */
    fun continuationRequest(previous: JsonObject, extraItems: List<JsonObject>): JsonObject {
        val base = responsesRequestJson.decodeFromJsonElement(ResponsesRequest.serializer(), previous)
        val nextInput = buildJsonArray {
            base.input.forEach { add(it) }
            extraItems.forEach { add(it) }
        }
        // What arrived before the cut may repeat what the request already carries: each call and reasoning item once.
        val next = base.copy(input = pairing.pair(nextInput))
        return responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), next) as JsonObject
    }
}
