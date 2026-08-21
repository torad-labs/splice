// NEW: the per-round outcome surface (usage, errors, tool-search calls,
// Success/Failure/abandon). Split from Turn.kt so the meta/watchdog file
// is not billed for the outcome catalogue (concentration, 2026-08-19).
// Same-package — callers keep splice.core.turn.{Usage,ErrorType,...}.
package splice.core.turn

import kotlinx.serialization.json.JsonObject

public data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    // Prompt-cache read: input_tokens_details.cached_tokens (Responses) / cache_read_input_tokens.
    // The whole point of prompt_cache_key — surfaced so the HUD and log can report the real hit rate.
    val cachedTokens: Long = 0,
    // output_tokens_details.reasoning_tokens (Responses). Drives reasoning-continuation fold
    // detection (the 518n-2 truncation fingerprint); NEVER part of the client usage payload.
    val reasoningTokens: Long = 0,
) {
    /** Sum two rounds' usage — reasoning-continuation folding accumulates across hidden rounds. */
    public operator fun plus(other: Usage): Usage = Usage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
    )
}

/** Anthropic error-event taxonomy the wire understands (error literals are not L3-gated). */
public enum class ErrorType(public val wireName: String) {
    INVALID_REQUEST("invalid_request_error"),
    AUTHENTICATION("authentication_error"),
    PERMISSION("permission_error"),
    NOT_FOUND("not_found_error"),
    RATE_LIMIT("rate_limit_error"),
    API_ERROR("api_error"),
    OVERLOADED("overloaded_error"),
}

/** A hosted tool call the round addressed to the GATEWAY (Responses `execution:"client"`), never
 *  to Claude Code. Value-typed id so a call_id can never be confused with a tool_use id. */
@JvmInline
public value class ToolSearchCallId(public val v: String)

public data class ToolSearchCall(
    val callId: ToolSearchCallId,
    val query: String,
    val limit: Int?,
    /** The item VERBATIM as the backend sent it — echoed into the continuation's input so the
     *  history item is the backend's own shape, never a re-authored guess. */
    val raw: JsonObject,
)

public sealed class TurnOutcome {
    /** Buffers ride the outcome (pinned P2-MACH slot): the gateway pipeline runs
     *  promote-to-text -> honesty gates -> mirror -> terminal AFTER the machine returns. */
    public data class Success(
        val hasToolUse: Boolean,
        val incomplete: Boolean,
        val usage: Usage,
        val thinkingText: String = "",
        val bodyText: String = "",
        val emittedText: Boolean = false,
        /** True when a THINKING block actually reached the sink this round (CX-09).
         *
         *  Distinct from [thinkingText] being non-empty: the harvest fallback fills the buffer from
         *  the completed response object WITHOUT touching the sink, so the buffer is a statement
         *  about what the model produced and this is a statement about what the CLIENT received.
         *  Only the latter can answer "did this turn put anything on the wire", which is the
         *  question the empty-turn honesty gate has to ask before calling a turn empty. */
        val emittedThinking: Boolean = false,
        /** splice-reasoning envelopes (base64) of THIS round's encrypted reasoning items, for
         *  reasoning-continuation replay. Populated only when the turn is fold-eligible; empty
         *  otherwise (opaque handles — the gateway forwards them to the provider's fold controller,
         *  never reads them). */
        val reasoningEnvelopes: List<String> = emptyList(),
        /** tool_search_call items THIS round emitted. Non-empty only on a responses turn with
         *  deferral active; the gateway never reads their contents — it hands them to the turn's
         *  ToolSearchController (the same opaque-forwarding rule as reasoningEnvelopes). */
        val toolSearches: List<ToolSearchCall> = emptyList(),
    ) : TurnOutcome()

    public data class Failure(
        val type: ErrorType,
        val message: String,
        /** True when a genuine upstream-reported error produced this failure (an error event/body
         *  the provider actually sent); false for locally-synthesized verdicts (watchdog stall,
         *  truncation-without-terminal). Drives the G20 health split — the old OVERLOADED-implies-
         *  local heuristic misattributed passthrough overloaded_error (review 2026-07-19). */
        val providerReported: Boolean = false,
        /** What the round had produced when it died — null when the dialect does not support
         *  mid-stream re-anchoring or the turn was cancelled (watchdog). Rides the outcome the
         *  same way Success buffers do (P2-MACH); the gateway never reads envelope contents. */
        val partial: PartialRound? = null,
        /** Output/reasoning genuinely burned by ABSORBED re-anchor rounds when the turn STILL
         *  failed — carried so the usage store and perf row do not under-report the exact turns
         *  that ran the most upstream rounds (review-pr 2026-07-24). Zero when no salvage. */
        val salvagedUsage: Usage = Usage(),
    ) : TurnOutcome()

    /** The salvageable state of a round that failed mid-stream, for continuation re-anchoring:
     *  the wire is already at a clean block boundary (translators closeAll before the terminal
     *  decision), so a continuation may APPEND — never replay. [toolTearOpen] marks the one
     *  non-continuable tear: a tool_use block swept shut with partial args JSON. [bodyText] and
     *  [reasoningEnvelopes] seed the continuation request; [thinkingText]/[emittedText] feed the
     *  cross-round merge so the final honesty gates and reasoning mirror see the WHOLE turn, not
     *  just the last round (code-review 2026-07-24: the pipeline is round-blind by itself). */
    public data class PartialRound(
        val thinkingText: String = "",
        val bodyText: String = "",
        val emittedText: Boolean = false,
        /** CX-09: this round put a thinking block on the REAL wire. Unlike [emittedText] this
         *  survives the buffered-round strip, because BufferingWireSink forwards openThinking /
         *  thinkingDelta straight to the real sink — only text and tool ops are held back. */
        val emittedThinking: Boolean = false,
        val hasToolUse: Boolean = false,
        val reasoningEnvelopes: List<String> = emptyList(),
        val toolTearOpen: Boolean = false,
        val usage: Usage = Usage(),
    )

    /** Client vanished mid-stream: nothing to emit, seal quietly (never an error frame). */
    public data object ClientAbandoned : TurnOutcome()
}
