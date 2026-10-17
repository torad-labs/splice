// NEW: 2026-10-08 — the Responses dialect's request builder, stream translator and re-anchor policy, as a
// sibling module's test needs them: through plain values and the public ports they implement. Their own
// types (BuildOptions, StreamTurnContext, ResponsesReanchorPolicy and what they carry) are `internal`
// to this module, so a provider's or the turn pipeline's test builds one here and not by naming them.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.parse.AnthropicTurnBody
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ReasoningDisplay
import splice.core.turn.SharedSummaryParts
import splice.dialect.responses.reasoning.EmitEncryptedReasoning
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.ReasoningEnvelopeDecoder
import splice.dialect.responses.reasoning.ReasoningEnvelopeEncoder
import splice.dialect.responses.reasoning.ResponsesReanchorPolicy
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ModelIds
import splice.dialect.responses.request.ReasoningHandoff
import splice.dialect.responses.request.RequestedReasoning
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.dialect.responses.stream.ResponsesStreamTranslator
import splice.upstream.ReanchorPolicy
import splice.upstream.StreamTranslator
import splice.upstream.TurnSignals

/** The request body the builder produces for [body] on [model]. Prior reasoning is decoded as a turn decodes it. */
fun buildResponsesTestRequest(
    quirks: ResponsesQuirks,
    body: AnthropicTurnBody,
    model: String,
    showReasoning: ReasoningDisplay = ReasoningDisplay.OFF,
    replayReasoning: Boolean = false,
): JsonObject = ResponsesRequestBuilder(quirks).build(
    body.typed,
    body.raw,
    BuildOptions(
        compact = false,
        models = ModelIds(
            original = "claude-codex--$model",
            upstream = model,
        ),
        reasoning = RequestedReasoning(
            effort = null,
            summary = null,
            display = showReasoning,
        ),
        handoff = ReasoningHandoff(
            replay = InjectPriorReasoning(replayReasoning),
            decode = ReasoningEnvelopeDecoder { ReasoningReplay.decodeReasoningEnvelope(it) },
        ),
    ),
).req

/** A translator for one round that never continues: a fresh [SharedSummaryParts] IS the turn's state. */
fun responsesTestTranslator(
    emitEncryptedReasoning: Boolean = false,
    collectReasoningEnvelopes: Boolean = false,
    encode: (JsonObject) -> String? = { null },
): StreamTranslator = ResponsesStreamTranslator(
    StreamTurnContext(
        compact = false,
        emitEncryptedReasoning = EmitEncryptedReasoning(emitEncryptedReasoning),
        encodeReasoningEnvelope = ReasoningEnvelopeEncoder { encode(it) },
        signals = TurnSignals(
            clientGone = { false },
            watchdogFired = { null },
        ),
        caps = WatchdogCaps(
            streamIdleMs = 180_000,
            upstreamTimeoutMs = 900_000,
        ),
        summary = SummaryHandling(
            partsShared = SharedSummaryParts(),
        ),
        reasoningCapture = ReasoningCapture(
            collectEnvelopes = collectReasoningEnvelopes,
        ),
    ),
)

/** The re-anchor policy a Responses turn runs under, cut off after [maxContinuations] continuations. */
fun responsesTestReanchor(
    maxContinuations: Int,
    decode: (String) -> JsonObject? = { null },
): ReanchorPolicy = ResponsesReanchorPolicy(ReasoningEnvelopeDecoder { decode(it) }, maxContinuations)
