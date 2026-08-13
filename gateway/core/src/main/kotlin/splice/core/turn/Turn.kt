// NEW: provider-neutral turn contracts (plan SPI shapes). TurnOutcome deliberately carries
// NO stop-reason string — hasToolUse/incomplete booleans only; the wire literal derivation
// (tool_use > max_tokens > end_turn) is sealed inside the gateway's SseEmitter (L3-as-types).
package splice.core.turn

import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

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
    /** Stable per-conversation scope key (responses dialect: first-message hash) — partitions the
     *  gateway reasoning cache so concurrent conversations on one head can never cross-inject
     *  (review 2026-07-24, RC-2's eli-risk-8 keying). Null (chat/passthrough) = one shared scope. */
    val conversationKey: String? = null,
    /** The client's session id when it sent one (ws-transport WS-3). [conversationKey] alone is a
     *  hash of the first user message's TEXT, so two conversations that open with identical words
     *  share it BY DESIGN — harmless for a cache miss, but fatal as a previous_response_id chain
     *  anchor, where it would hand one conversation's server-side context to another. Null when
     *  the client sends no session id; consumers must mix BOTH, never either alone. */
    val sessionId: String? = null,
    /** Tool-surface partition sizes for THIS turn's request; null when deferral was not in play.
     *  Non-null stamps the perf counters even at zero — a deploy where tools_deferred stays 0 is a
     *  false landing, and it must be visible in one grep of the perf JSONL. */
    val toolsEager: Int? = null,
    val toolsDeferred: Int? = null,
    /** Turn-scoped summary-dedup state shared by every continuation round's translator (rounds
     *  build fresh translators; without a shared set, a section re-titled by a continuation round
     *  passes each round's per-instance dedup and lands as a duplicate — the 2026-07-26 mirror
     *  duplication).
     *
     *  NON-NULL WITH A FRESH DEFAULT ON PURPOSE (2026-07-26): this is the ONLY sanctioned
     *  construction site. No caller passes this argument, so there is no per-round construction to
     *  get wrong, and `copy()` — which every continuation path uses — preserves the reference.
     *  Dialects that render no reasoning summary simply never read it (two empty collections). */
    val summaryParts: SharedSummaryParts = SharedSummaryParts(),
)

/** The shared state behind TurnMeta.summaryParts: the ordered parts already emitted to the
 *  client this turn, plus the per-item exact set the dedup's within-item arm matches against.
 *  Mutable per-turn coordination, never compared by value.
 *
 *  ACCESS DISCIPLINE (2026-07-26 review): mutated by ONE round's translator at a time. The
 *  fold/re-anchor/tool_search loops drive rounds strictly sequentially (`FoldRunner.run` is a
 *  plain `while (true) { postRound(...) }` — no launch/async around a round), so the absence of
 *  synchronization here is deliberate, not an oversight. A future round loop that overlaps rounds
 *  must add synchronization before sharing this. Public, not internal: the dialect module reads
 *  these across a module boundary.
 *
 *  APPEND-ONLY BY TYPE, not by comment (2026-07-27 review): the collections used to be public
 *  `MutableList`/`MutableMap`, so the discipline above was documentary — any in-repo caller could
 *  `clear()`, reorder or truncate the list with no compile error and no test to catch it, and the
 *  translator's recap cursor trusts this list's ORDER and LENGTH. The surface is now exactly the
 *  two operations the dedup dialect performs; the collections cannot be reached to be reordered. */
public class SharedSummaryParts {
    private val emittedParts = mutableListOf<String>()
    private val itemEmitted = mutableMapOf<Int, MutableSet<String>>()

    /** The part at [cursor] in emission order, or null past either end (the recap arm passes
     *  RECAP_DONE = -1 once an item's leading recap is finished, which must not match). */
    public fun partAt(cursor: Int): String? = emittedParts.getOrNull(cursor)

    /** Records [part] as emitted for item [outputIndex]. Returns false when it was ALREADY
     *  emitted for that item — a dedup hit — in which case nothing is appended. */
    public fun markEmitted(outputIndex: Int, part: String): Boolean {
        val fresh = itemEmitted.getOrPut(outputIndex) { mutableSetOf() }.add(part)
        if (fresh) emittedParts.add(part)
        return fresh
    }
}

/** The two-tier watchdog knobs (v35 doctrine): before first byte the idle limit is
 *  firstByteTimeout (prefill is legitimately silent for minutes); after, streamIdle;
 *  totalCap bounds the whole turn. */
public data class WatchdogBudget(
    val firstByteTimeout: Duration,
    val streamIdle: Duration,
    val totalCap: Duration,
)
