// NEW: reasoning-continuation folding for the codex Responses path. gpt-5.5 / gpt-5.6-luna /
// gpt-5.6-terra intermittently TRUNCATE their own chain-of-thought at exactly
// reasoning_tokens == 518*n - 2 (516, 1034, 1552, ...) — the model stops reasoning early and
// finalizes a shallower answer. gpt-5.6-sol does NOT (probed 2026-07-19). The fix (the mechanism
// CodexCont / codexcomp use): detect the fingerprint, REPLAY the round's reasoning.encrypted_content
// back to the server with a phase:commentary "Continue thinking..." marker so the model resumes from
// the cutoff instead of finalizing. encrypted_content is opaque — replayed, never read (the server
// holds the keys). The envelope round-trip reuses core/reasoning/Replay.kt (encode on the stream
// side into TurnOutcome.Success.reasoningEnvelopes, decode HERE back to Responses reasoning input
// items) — the codec is never re-authored.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.spi.FoldController
import splice.spi.FoldRound

/** Operator-tunable reasoning-continuation policy (threaded from config like mirror_reasoning). */
public data class FoldConfig(
    /** Upstream models that exhibit the 518n-2 truncation. Default luna/terra/5.5 — NOT sol. */
    val models: Set<String>,
    val maxContinue: Int = DEFAULT_MAX_CONTINUE,
    val markerText: String = DEFAULT_MARKER_TEXT,
    val maxTierN: Int = DEFAULT_MAX_TIER_N,
) {
    public companion object {
        public const val DEFAULT_MAX_CONTINUE: Int = 3
        public const val DEFAULT_MAX_TIER_N: Int = 6
        public const val DEFAULT_MARKER_TEXT: String = "Continue thinking..."
        public val DEFAULT_MODELS: Set<String> = setOf("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.5")
    }
}

/**
 * The codex 518n-2 detector + continuation-request builder. One per fold-eligible turn; the gateway
 * drives it after each completed round. Detection is the shipped fingerprint (516/1034/1552/...);
 * a truncated round with replayable encrypted reasoning, within the tier window and continuation
 * cap, yields the NEXT request body (this round's input + replayed reasoning + the marker). Anything
 * else returns null → the gateway flushes the buffered output and emits the single honest terminal.
 */
public class ResponsesFoldController(
    private val config: FoldConfig,
    private val decodeReasoningEnvelope: (String) -> JsonObject?,
) : FoldController {

    override fun continuation(round: FoldRound): JsonObject? {
        if (!shouldContinue(round)) return null
        val replay = round.outcome.reasoningEnvelopes.mapNotNull { decodeReasoningEnvelope(it) }
        return if (replay.isEmpty()) null else continuationBody(round.requestBody, replay)
    }

    /** Truncated on the 518n-2 fingerprint, within the tier window, and under the continuation cap. */
    private fun shouldContinue(round: FoldRound): Boolean {
        val reasoningTokens = round.outcome.usage.reasoningTokens
        return isTruncationFingerprint(reasoningTokens) &&
            tierOf(reasoningTokens) <= config.maxTierN &&
            round.roundIndex < config.maxContinue
    }

    private fun continuationBody(previous: JsonObject, replayItems: List<JsonObject>): JsonObject =
        continuationRequest(previous, replayItems + listOf(continuationMarker()))

    // A hidden phase:commentary assistant message — the codex-rs / CodexCont / codexcomp nudge.
    // It ALSO satisfies the Responses "reasoning item needs a following item" constraint.
    private fun continuationMarker(): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("phase", "commentary")
        put("content", config.markerText)
    }

    public companion object {
        // reasoning_tokens == 518*n - 2  ⇔  (reasoning_tokens + 2) % 518 == 0, reasoning_tokens > 0.
        private const val FOLD_PERIOD = 518L
        private const val FINGERPRINT_OFFSET = 2L

        /** The exact 518n-2 truncation fingerprint (516/1034/1552/...). */
        public fun isTruncationFingerprint(reasoningTokens: Long): Boolean =
            reasoningTokens > 0 && (reasoningTokens + FINGERPRINT_OFFSET) % FOLD_PERIOD == 0L

        /** Tier n for a fingerprint-matching count (516→1, 1034→2, ...). */
        public fun tierOf(reasoningTokens: Long): Long = (reasoningTokens + FINGERPRINT_OFFSET) / FOLD_PERIOD
    }
}

/** DTO-faithful continuation shared by fold and re-anchor: decode the prior request, extend ONLY
 *  its `input` with [extraItems], re-encode through the same closed serializer. No request FIELD
 *  is added — the #924 closed DTO is untouched, so byte-identity off both paths is trivial. */
internal fun continuationRequest(previous: JsonObject, extraItems: List<JsonObject>): JsonObject {
    val base = responsesRequestJson.decodeFromJsonElement(ResponsesRequest.serializer(), previous)
    val nextInput = buildJsonArray {
        base.input.forEach { add(it) }
        extraItems.forEach { add(it) }
    }
    val next = base.copy(input = nextInput)
    return responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), next) as JsonObject
}
