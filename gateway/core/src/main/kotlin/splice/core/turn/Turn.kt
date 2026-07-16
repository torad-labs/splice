// NEW: provider-neutral turn contracts (plan SPI shapes). TurnOutcome deliberately carries
// NO stop-reason string — hasToolUse/incomplete booleans only; the wire literal derivation
// (tool_use > max_tokens > end_turn) is sealed inside the gateway's SseEmitter (L3-as-types).
package splice.core.turn

import kotlin.time.Duration

public data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
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

public sealed interface TurnOutcome {
    public data class Success(
        val hasToolUse: Boolean,
        val incomplete: Boolean,
        val usage: Usage,
    ) : TurnOutcome

    public data class Failure(
        val type: ErrorType,
        val message: String,
    ) : TurnOutcome

    /** Client vanished mid-stream: nothing to emit, seal quietly (never an error frame). */
    public data object ClientAbandoned : TurnOutcome
}

/** Per-turn facts the pipeline threads through translation and streaming (meta replaces
 *  the v29 body.__claudex* side channel — pure data, never smuggled on the request). */
public data class TurnMeta(
    val compact: Boolean,
    val showReasoning: String,
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
