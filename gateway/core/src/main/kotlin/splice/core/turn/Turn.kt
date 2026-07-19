// NEW: provider-neutral turn contracts (plan SPI shapes). TurnOutcome deliberately carries
// NO stop-reason string — hasToolUse/incomplete booleans only; the wire literal derivation
// (tool_use > max_tokens > end_turn) is sealed inside the gateway's SseEmitter (L3-as-types).
package splice.core.turn

import kotlin.time.Duration

public data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    // Prompt-cache read: input_tokens_details.cached_tokens (Responses) / cache_read_input_tokens.
    // The whole point of prompt_cache_key — surfaced so the HUD and log can report the real hit rate.
    val cachedTokens: Long = 0,
)

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
    ) : TurnOutcome()

    public data class Failure(
        val type: ErrorType,
        val message: String,
        /** True when a genuine upstream-reported error produced this failure (an error event/body
         *  the provider actually sent); false for locally-synthesized verdicts (watchdog stall,
         *  truncation-without-terminal). Drives the G20 health split — the old OVERLOADED-implies-
         *  local heuristic misattributed passthrough overloaded_error (review 2026-07-19). */
        val providerReported: Boolean = false,
    ) : TurnOutcome()

    /** Client vanished mid-stream: nothing to emit, seal quietly (never an error frame). */
    public data object ClientAbandoned : TurnOutcome()
}

/** Per-turn facts the pipeline threads through translation and streaming (meta replaces
 *  the v29 body.__claudex* side channel — pure data, never smuggled on the request). */
public data class TurnMeta(
    val compact: Boolean,
    val showReasoning: ReasoningDisplay,
    val stream: Boolean,
    val originalModel: String,
    val upstreamModel: String,
    val clientMaxTokens: Long?,
    val effort: String,
    val summary: String?,
    val budgetTokens: Long?,
)

/** The two-tier watchdog knobs (v35 doctrine): before first byte the idle limit is
 *  firstByteTimeout (prefill is legitimately silent for minutes); after, streamIdle;
 *  totalCap bounds the whole turn. */
public data class WatchdogBudget(
    val firstByteTimeout: Duration,
    val streamIdle: Duration,
    val totalCap: Duration,
)
