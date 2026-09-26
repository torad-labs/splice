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
    // V4-85: prompt-cache WRITE — cache_creation_input_tokens, or the sum of Anthropic's per-TTL
    // `cache_creation` buckets. Like [cachedTokens] this is a DISJOINT part of [inputTokens], not an
    // addition to it, and it exists because a cache write bills at its own premium rate: folded into
    // inputTokens alone it was indistinguishable from a cache MISS and billed as one. Zero on every
    // dialect whose wire reports no such bucket (ChatUsage) — those heads never write a cache.
    //
    // APPENDED LAST, deliberately: Usage is constructed positionally as Usage(19, 7, 5, 3) in the
    // code-mode and custom-call pins, so inserting it beside cachedTokens where it semantically
    // belongs would silently re-read those four literals as a different set of buckets.
    val cacheWriteTokens: Long = 0,
) {
    /** Sum two rounds' usage — reasoning-continuation folding accumulates across hidden rounds. */
    public operator fun plus(other: Usage): Usage = Usage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
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

/**
 * The connection-tear ending, named ONCE (V4-67).
 *
 * TWO SPELLINGS ARE IN PLAY and they are why this is here rather than written where it is used:
 * [CONN_RESET_KIND] is the journal label (`turn ERROR conn-reset ...`) and [CONN_RESET_OUTCOME] is
 * the perf-row tag (`outcome=error:conn-reset`) — the same tag under the `error:` prefix every
 * locally-classified ending uses. The second is DERIVED from the first at compile time, so the
 * pair cannot drift the way the two literals in TurnConnEnd just did. This tag is the only string
 * in the live journal that names this failure class, and it is what the operator greps: the row
 * that made a torn stream continuable is the row that would otherwise have hidden its successor,
 * because a converted tear finishes through the pipeline instead of the conn-reset surface.
 *
 * In core beside [ErrorType] because BOTH sides now read it: splice.head (internal) and
 * splice.head.pipeline (public) cannot see each other, and a copy in each is the drift.
 */
public const val CONN_RESET_KIND: String = "conn-reset"

/** The perf-row outcome tag for [CONN_RESET_KIND] — derived, never re-spelled. */
public const val CONN_RESET_OUTCOME: String = "error:$CONN_RESET_KIND"

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

/** A gateway-local custom tool call captured verbatim from a Responses output item. */
public data class GatewayCustomCall(
    val callId: String,
    val name: String,
    val input: String,
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
        /** A `message` output item COMPLETED this round, whether or not it carried text.
         *
         *  An empty message is the model's finished answer, not an absence: given nothing to add
         *  (Astra after a Stop hook echoes an end-of-turn report back at it, 2026-09-05) it closes
         *  a message with no text, exactly as codex renders — codex ends the turn there, since
         *  only a tool call sets `needs_follow_up`. Grading that turn `empty_model` handed Claude
         *  Code an API error it retried a dozen times per incident, so the empty-turn gate reads
         *  this first: a closed message ends clean; a round with NO message item stays the honest
         *  error it always was. */
        val messageClosed: Boolean = false,
        /** What the completed upstream response actually held, item by item, in one short line
         *  (`status=completed items=[reasoning(summary=0,enc=1842) message(output_text:0)]`).
         *  Evidence only: read by the empty-turn line so a "no content" verdict names the shape
         *  the backend sent instead of asserting an absence nobody can grep. */
        val outputShape: String = "",
        /** splice-reasoning envelopes (base64) of THIS round's encrypted reasoning items, for
         *  reasoning-continuation replay. Populated only when the turn is fold-eligible; empty
         *  otherwise (opaque handles — the gateway forwards them to the provider's fold controller,
         *  never reads them). */
        val reasoningEnvelopes: List<String> = emptyList(),
        /** tool_search_call items THIS round emitted. Non-empty only on a responses turn with
         *  deferral active; the gateway never reads their contents — it hands them to the turn's
         *  ToolSearchController (the same opaque-forwarding rule as reasoningEnvelopes). */
        val toolSearches: List<ToolSearchCall> = emptyList(),
        /** Gateway-local custom calls. Empty keeps every non-bridge outcome byte-identical. */
        val customCalls: List<GatewayCustomCall> = emptyList(),
    ) : TurnOutcome()

    public data class Failure(
        val message: String,
        /** V4-117: WHY this turn failed. REQUIRED, with no default, so the compiler is the wall:
         *  a site that forgets it does not compile, which is stronger than the ast-grep rule that
         *  used to police this. The value is the truth about the turn, never what the client is
         *  told — see [phase] and the derived [type]. */
        val cause: FailureCause,
        /** V4-117: HOW FAR the turn had got, as the site knows it. The boundary — which alone
         *  knows whether a client frame actually went out — corrects this with copy(phase = ...),
         *  and [type] follows, so nobody authors the wire type. */
        val phase: FailurePhase,
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
        /** True when the SAME request produces the SAME failure — a verdict the gateway reached on
         *  its own (a code-mode record it cannot resume, a script the runtime cannot admit), which
         *  no retry can change. Rendered as a readable ending the client shows verbatim rather than
         *  an SSE error event: Claude Code 2.1.x re-sends an `api_error` identically until it gives
         *  up when it arrives before content, and after content replaces the message with a fixed
         *  "Server error mid-response" line (87 and 47 identical turns on 2026-09-07). */
        val deterministic: Boolean = false,
        /** V4-81: NO retry can change this verdict — an identical re-send reproduces it exactly.
         *
         *  Distinct from [deterministic], which is about the ENDING'S SHAPE (words the client
         *  renders vs an error event); this is about whether the failure is RE-ATTEMPTABLE, and it
         *  is what the pre-content wire-type rule reads. Advertising such a failure as transient is
         *  the expensive lie: RetryPolicy arms a cooldown only for RATE_LIMITED, so with
         *  CLAUDE_CODE_RETRY_WATCHDOG=1 a relabelled permanent failure makes the client re-send the
         *  identical bytes up to 300 times, six upstream attempts each, for a verdict that cannot
         *  move. Set from the classifier's `transient = false` (UpstreamFailureClassifier), from a
         *  deterministic refusal (ResponsesTerminalDecision), and from the local base_url parse —
         *  the operator law "always a retry armed" is about failures a retry can HEAL.
         *
         *  Defaulted false: every construction that does not know stays exactly as it was, and the
         *  rule treats "unknown" as retryable, which is today's behavior. */
        val permanent: Boolean = false,
        /** V4-67: a connection tear the GATEWAY synthesized into an outcome (SseRoundDriver
         *  .tearOutcome) rather than letting it escape to the conn-reset surface. Carried so the
         *  ending keeps the [CONN_RESET_OUTCOME] tag whatever path it finishes through: a
         *  converted tear that no controller continues is finished by the pipeline, and without
         *  this it recorded `failure:overloaded_error` — leaving the one string that names this
         *  failure class absent from the perf row it is grepped in. Defaulted false, so every
         *  other construction of this type is byte-unchanged. */
        val connReset: Boolean = false,
        /** V4-117: how many upstream attempts the retry loop made before this failure, stamped by
         *  the LOOP (UpstreamFailed.layers) and carried here so the perf row can record it as
         *  layers=<n>. Zero is the honest default: a failure that never reached the loop — a
         *  watchdog, a refusal, a locally-decided verdict — genuinely had no attempts to report, and
         *  a caller that has no count must not be forced to invent one. */
        val layers: Int = 0,
    ) : TurnOutcome() {

        /** V4-117: DERIVED, never passed. The retry class the client keys on is a function of what
         *  went wrong and how far the turn had got, so the site states the cause and the phase and
         *  this follows — which is what makes it impossible for a failure and the wire to disagree.
         *  It used to be a constructor argument, hand-picked at twenty-seven sites, and the emitter
         *  relabelled it again for the pre-content case; now there is one author and the pre-content
         *  rule is a property of [WireType] that the phase alone selects. */
        val type: ErrorType get() = WireType.of(cause, phase)
    }

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

    /** Client vanished mid-stream: nothing to emit, seal quietly (never an error frame).
     *  [salvagedUsage] (DR-125): billed usage from rounds absorbed BEFORE the hang-up — real
     *  spend the vendor charged whether or not the client stayed to read the answer. The
     *  abandoning round itself reports nothing (its stream died unparsed), so this is the
     *  accumulator alone, and finishTurn stamps it exactly like a Failure's salvage. */
    public data class ClientAbandoned(val salvagedUsage: Usage = Usage()) : TurnOutcome()
}
