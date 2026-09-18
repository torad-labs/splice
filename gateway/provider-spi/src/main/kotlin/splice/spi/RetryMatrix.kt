// NEW: V4-117 (error taxonomy, 2026-09-18) — the layered retry strategy, keyed on (cause, phase).
//
// THE OPERATOR RULING THIS EXISTS FOR: errors shown to users must be accurate and descriptive, and
// every single possible error must have a multi-layer retry strategy. Before this the strategy was
// implicit — spread across RetryRules' status plan, ReissueRules' G5 interlock, the re-anchor
// controller's budget and the pool's rotation — and nothing enumerated what a given failure was
// actually entitled to. A failure could therefore be healable by NO layer and simply fall to the
// client, which is the shape behind this row's scar: a 301s upstream silence surfaced as
// OVERLOADED with its cause living only in free text.
//
// THE MATRIX IS TWO FILTERS, NOT A TABLE OF 76 HAND-WRITTEN ROWS. What a failure is entitled to is
// the intersection of (a) what its CAUSE can ever be healed by and (b) what its PHASE still
// permits. Both are written once, each with the reason it carries, and [RetryMatrix.of] intersects
// them. The alternative — nineteen causes times four phases filled in by hand — is seventy-six
// opportunities to disagree with ourselves, and the disagreements would be invisible.
//
// WHY THE PHASE FILTER IS NOT NEGOTIABLE: layers L1-L4 all SEND something upstream. Before the
// client has seen a byte that is safe; after it has, a re-issue duplicates output the user already
// read. So MID_OUTPUT admits only the layers that CONTINUE from what was delivered, and TERMINAL
// admits none of them — a verdict is not an interruption to repair.
//
// VISIBILITY: INTERNAL, and that is the ratchet's own first remedy rather than a convenience. The
// consumer of this matrix is the retry loop in THIS module (RetryRules, provider-spi), and the
// enumeration test is a friend of this module's test source set, so `public` would declare a surface
// no other module consumes — exactly what checks/public-surface.py reds as unjustified, which is the
// same defect V4-122 item 9 cleared on UpstreamTransport's five backoff consts. The first draft of
// this file was public and pushed the measured surface 167 -> 169; narrowing it removes that debt
// rather than recording it.
//
// BUDGETS ARE NAMED, NEVER RESTATED. Each layer carries the name of the declaration that owns its
// budget rather than a number, because a number copied here is a number that can drift from the one
// the loop actually enforces (the V4-100 single-sourcing rule, and why `budgetOwner` is a String).
package splice.spi

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase

/**
 * One layer of the retry strategy, in the order they are attempted. The ladder is walked in this
 * declaration order — cheapest repair first, the client's own retry last.
 */
internal enum class RetryLayer(val budgetOwner: String) {
    /** Re-send the request on the transport, on the shared backoff curve. */
    L1_TRANSPORT("RetryRules.maxRetries and the UpstreamTransport backoff curve"),

    /** Re-issue the stream after a 2xx but before any client frame (the G5 interlock). */
    L2_PRE_CONTENT_REISSUE("MAX_STREAM_REISSUES"),

    /** Resume from the text already delivered, by re-anchoring the round. */
    L3_REANCHOR_RESUME("ResponsesReanchorController's DEFAULT_MAX_CONTINUATIONS"),

    /** Switch to another account in the provider's pool and send the request again. */
    L4_ACCOUNT_SWITCH("the pool's own rotation, driven by AccountSelection"),

    /** Give the client a retryable wire type and a Retry-After, and let it decide. */
    L5_CLIENT_RETRY_CLASS("the wire type on the terminal frame, plus the Retry-After header"),
}

/** What a failure is entitled to, and the sentence explaining it. [reason] is never blank. */
internal data class FailureDisposition(
    val layers: List<RetryLayer>,
    val reason: String,
)

/** A cause's ceiling: the layers it can ever be healed by, and why. */
private data class CauseCeiling(val layers: List<RetryLayer>, val reason: String)

// Every layer that sends the whole request again. Named once because four causes share it and a
// fourth copy of this list is a fourth place for it to fall out of step.
private val SEND_AGAIN: List<RetryLayer> = listOf(
    RetryLayer.L1_TRANSPORT,
    RetryLayer.L2_PRE_CONTENT_REISSUE,
    RetryLayer.L3_REANCHOR_RESUME,
    RetryLayer.L4_ACCOUNT_SWITCH,
)

// WHAT EACH CAUSE CAN EVER BE HEALED BY. The phase filter may narrow this; it can never widen it,
// which is why a cause healable by nothing cannot become healable at some later phase.
private val CAUSE_CEILINGS: Map<FailureCause, CauseCeiling> = mapOf(
    FailureCause.UPSTREAM_STALLED to CauseCeiling(
        SEND_AGAIN,
        "the upstream stopped talking after delivering content, so the turn is " +
            "resumable from what was already sent.",
    ),
    FailureCause.UPSTREAM_TRUNCATED to CauseCeiling(
        listOf(
            RetryLayer.L2_PRE_CONTENT_REISSUE,
            RetryLayer.L3_REANCHOR_RESUME,
            RetryLayer.L4_ACCOUNT_SWITCH,
        ),
        "the stream ended with no terminal, so whether the turn is recoverable " +
            "depends entirely on what was delivered before it stopped.",
    ),
    FailureCause.UPSTREAM_CONN_RESET to CauseCeiling(
        listOf(RetryLayer.L1_TRANSPORT, RetryLayer.L4_ACCOUNT_SWITCH),
        "the socket failed rather than the model, so a fresh connection is the " +
            "whole repair.",
    ),
    FailureCause.UPSTREAM_STATUS_5XX to CauseCeiling(
        SEND_AGAIN,
        "a server-side fault, which is the class a retry can genuinely heal.",
    ),
    FailureCause.UPSTREAM_STATUS_4XX to CauseCeiling(
        listOf(RetryLayer.L4_ACCOUNT_SWITCH),
        "a 4xx is the upstream's verdict on the REQUEST, so identical bytes " +
            "re-earn the identical answer and only a different account can change it.",
    ),
    FailureCause.UPSTREAM_REPORTED to CauseCeiling(
        SEND_AGAIN,
        "an in-band failure with no status takes the generic bounded curve under " +
            "RETRY DEFAULT IS TOTAL, unless its vendor type string matches a terminal entry.",
    ),
    FailureCause.MODEL_REFUSED to CauseCeiling(
        emptyList(),
        "the model answered and the answer was no; the identical prompt is " +
            "refused identically, so a retry buys the same verdict at full price.",
    ),
    FailureCause.CONTENT_FILTERED to CauseCeiling(
        emptyList(),
        "the same generation is filtered identically; nothing below the client can " +
            "change that verdict.",
    ),
    FailureCause.TOOL_TEAR to CauseCeiling(
        emptyList(),
        "the tool-call arguments are ours to have damaged, and a continuation would " +
            "append to a block that cannot be repaired.",
    ),
    FailureCause.DIALECT_UNSUPPORTED to CauseCeiling(
        emptyList(),
        "the backend asked for a shape this dialect cannot express; re-sending the " +
            "same request asks the same question.",
    ),
    FailureCause.CODE_MODE_PROTOCOL to CauseCeiling(
        emptyList(),
        "a local protocol-state failure; no upstream layer can repair state we " +
            "hold, which is why its recovery is its own path and not a retry.",
    ),
    FailureCause.INTERNAL to CauseCeiling(
        SEND_AGAIN,
        "a failure we could not attribute; the retry default is TOTAL, so it is " +
            "retried as though it were transient rather than written off.",
    ),
    FailureCause.VENDOR_RATE_LIMITED to CauseCeiling(
        listOf(RetryLayer.L1_TRANSPORT, RetryLayer.L4_ACCOUNT_SWITCH),
        "the vendor is limiting this account; the wait comes from the vendor's own " +
            "reset, and another account may be clear.",
    ),
    FailureCause.VENDOR_QUOTA_EXHAUSTED to CauseCeiling(
        listOf(RetryLayer.L4_ACCOUNT_SWITCH),
        "this account's quota is spent and waiting does not return it within a " +
            "turn, so the pool is the only layer that helps.",
    ),
    FailureCause.AUTH_MISSING to CauseCeiling(
        listOf(RetryLayer.L4_ACCOUNT_SWITCH),
        "there is no credential to send; another account in the pool is the only " +
            "thing that can produce one.",
    ),
    FailureCause.AUTH_REFRESH_FAILED to CauseCeiling(
        listOf(RetryLayer.L4_ACCOUNT_SWITCH),
        "the credential was refreshed and rejected again, so the bytes would be " +
            "identical; the ladder is evict and rotate, not re-send.",
    ),
    FailureCause.POOL_EXHAUSTED to CauseCeiling(
        emptyList(),
        "every account in the pool has been tried, so there is no next account for " +
            "L4 to reach.",
    ),
    FailureCause.ADMISSION_FULL to CauseCeiling(
        emptyList(),
        "our own admission gate refused the turn, so nothing reached the upstream " +
            "and no upstream layer has anything to heal.",
    ),
    FailureCause.REQUEST_TOO_LARGE to CauseCeiling(
        emptyList(),
        "the request exceeds a bound we enforce ourselves, so re-sending it is the " +
            "same request.",
    ),
)

// WHAT EACH PHASE STILL PERMITS — "which layers are LEGAL once the turn has reached here", which is
// a different question from whether they would help.
private val PHASE_LEGALITY: Map<FailurePhase, Pair<Set<RetryLayer>, String>> = mapOf(
    FailurePhase.CONNECT to (
        setOf(
            RetryLayer.L1_TRANSPORT,
            RetryLayer.L4_ACCOUNT_SWITCH,
            RetryLayer.L5_CLIENT_RETRY_CLASS,
        ) to
            "Nothing was handed off yet, so a fresh attempt is free; there is no " +
            "stream to re-issue and no delivered text to resume."
        ),
    FailurePhase.FIRST_BYTE to (
        setOf(
            RetryLayer.L1_TRANSPORT,
            RetryLayer.L2_PRE_CONTENT_REISSUE,
            RetryLayer.L4_ACCOUNT_SWITCH,
            RetryLayer.L5_CLIENT_RETRY_CLASS,
        ) to
            "The stream was accepted but the client has seen no frame, which is the " +
            "one window where re-issuing cannot duplicate output."
        ),
    FailurePhase.MID_OUTPUT to (
        setOf(
            RetryLayer.L3_REANCHOR_RESUME,
            RetryLayer.L4_ACCOUNT_SWITCH,
            RetryLayer.L5_CLIENT_RETRY_CLASS,
        ) to
            "Content already reached the client, so a re-issue would duplicate it; " +
            "only the layers that CONTINUE from what was sent remain."
        ),
    FailurePhase.TERMINAL to (
        setOf(RetryLayer.L5_CLIENT_RETRY_CLASS) to
            "The turn reached a terminal, so this is a verdict rather than an " +
            "interruption and there is nothing to continue."
        ),
)

/**
 * The layered retry strategy for a failure, as a value. [of] answers for EVERY (cause, phase) pair,
 * and the enumeration is a test rather than a convention here: a cause added to the enum with no
 * ceiling fails closed at [CAUSE_CEILINGS] instead of silently returning an empty disposition.
 */
internal object RetryMatrix {

    // The cell a pair with no layer prints. An em dash rather than an empty cell, so a reader can
    // tell "entitled to nothing" from "the table is missing a row".
    private const val NO_LAYER = "—"

    /**
     * The operator-facing table, RENDERED FROM THIS CODE so it cannot come to describe a matrix we
     * do not have. AGENTS.md carries the output between markers and RetryMatrixTableTest fails on
     * any drift, which is the point: a hand-maintained copy of this table would be a second author
     * for the same facts, and the two would disagree in silence.
     */
    fun table(): String {
        val header = "| cause | " + FailurePhase.entries.joinToString(" | ") { it.name } + " |"
        val rule = "|---|" + FailurePhase.entries.joinToString("") { "---|" }
        val rows = FailureCause.entries.map { cause ->
            val cells = FailurePhase.entries.map { phase ->
                // L1..L5, derived from the enum name so a new layer needs no second spelling here.
                of(cause, phase).layers.joinToString(" ") { it.name.substringBefore('_') }.ifEmpty { NO_LAYER }
            }
            "| " + cause.name + " | " + cells.joinToString(" | ") + " |"
        }
        return (listOf(header, rule) + rows).joinToString("\n")
    }

    /** What a turn that failed with [cause] at [phase] is entitled to. */
    fun of(cause: FailureCause, phase: FailurePhase): FailureDisposition {
        val ceiling = CAUSE_CEILINGS.getValue(cause)
        val (legal, phaseReason) = PHASE_LEGALITY.getValue(phase)
        val layers = ceiling.layers.filter { it in legal }
        // The phase narrowed it, and the explanation has to SAY so — otherwise a reader sees
        // "entitled to nothing" and cannot tell a deterministic verdict from a window that had
        // already closed, which are opposite facts about the same turn.
        val reason = if (layers.size == ceiling.layers.size) {
            ceiling.reason
        } else {
            ceiling.reason + " At this phase: " + phaseReason
        }
        return FailureDisposition(layers, reason)
    }
}
