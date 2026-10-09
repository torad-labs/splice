package splice.core.turn

/** What kind of failure a [TurnOutcome.Failure] is, for health attribution and for the retry rule. */
public data class FailureTraits(
    /** True when a genuine upstream-reported error produced this failure (an error event/body
     *  the provider actually sent); false for locally-synthesized verdicts (watchdog stall,
     *  truncation-without-terminal). Drives the G20 health split — the old OVERLOADED-implies-
     *  local heuristic misattributed passthrough overloaded_error (review 2026-07-19). */
    val providerReported: Boolean = false,
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
)
