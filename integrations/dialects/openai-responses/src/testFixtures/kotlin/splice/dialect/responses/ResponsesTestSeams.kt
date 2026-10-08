// NEW: 2026-10-08 — the Responses dialect's request builder, stream translator and re-anchor policy, as a
// sibling module's test needs them: through plain values and the public ports they implement. Their own
// types (BuildOptions, StreamTurnContext, ResponsesReanchorController and what they carry) are `internal`
// to this module, so a provider's or the turn pipeline's test builds one here and not by naming them.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.SharedSummaryParts
import splice.dialect.responses.reasoning.EmitEncryptedReasoning
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.ReasoningEnvelopeDecoder
import splice.dialect.responses.reasoning.ReasoningEnvelopeEncoder
import splice.dialect.responses.reasoning.ResponsesReanchorController
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.dialect.responses.stream.ResponsesStreamTranslator
import splice.upstream.ReanchorPolicy
import splice.upstream.StreamTranslator

/** The request body the builder produces for [body] on [model], with prior reasoning decoded by [decode]. */
fun buildResponsesTestRequest(
    quirks: ResponsesQuirks,
    body: AnthropicTurnBody,
    model: String,
    showReasoning: ReasoningDisplay = ReasoningDisplay.OFF,
    replayReasoning: Boolean = false,
    decode: (String) -> JsonObject? = { null },
): JsonObject = ResponsesRequestBuilder(quirks).build(
    body.typed,
    body.raw,
    BuildOptions(
        compact = false,
        originalModel = "claude-codex--$model",
        upstreamModel = model,
        configEffort = null,
        configSummary = null,
        showReasoning = showReasoning,
        replayReasoning = InjectPriorReasoning(replayReasoning),
        decodeReasoningEnvelope = ReasoningEnvelopeDecoder { decode(it) },
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
        clientGone = { false },
        watchdogFired = { null },
        streamIdleMsForMessage = 180_000,
        upstreamTimeoutMsForMessage = 900_000,
        summaryPartsShared = SharedSummaryParts(),
        collectReasoningEnvelopes = collectReasoningEnvelopes,
    ),
)

/** The re-anchor policy a Responses turn runs under, cut off after [maxContinuations] continuations. */
fun responsesTestReanchor(
    maxContinuations: Int,
    decode: (String) -> JsonObject? = { null },
): ReanchorPolicy = ResponsesReanchorController(ReasoningEnvelopeDecoder { decode(it) }, maxContinuations)
