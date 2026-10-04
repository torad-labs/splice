// NEW: V4-117 (error taxonomy, 2026-09-18) — WHY a turn failed, as a value distinct from the WIRE
// TYPE the client keys its own retry on. Today they are ONE field: `ErrorType.OVERLOADED` is what
// splice writes on the wire so the client retries, and FailureText derives the operator-facing code
// SPLICE-OVERLOADED from that same value — so a 301s upstream silence surfaced as OVERLOADED with
// the actual cause living only in free text. The operator ruling is that errors shown to users must
// be accurate and descriptive, and that every one must have a layered retry strategy. A retry
// strategy is keyed on the CAUSE, so the cause has to be a value rather than a substring.
//
// THE SET BELOW IS DERIVED, NOT INHERITED. The row's design brief carried a candidate list; the
// instruction was to derive the set by reading every `TurnOutcome.Failure(` site instead, and the
// derivation disagrees with that list in BOTH directions. The census is the ast-grep rule
// `quality/rules/kotlin/kt-failure-has-cause.yml` (26 sites in 12 files, independently corroborated
// this session), and every cause below names the sites that produce it.
//
//   IN THE BRIEF, PRODUCED BY NO SITE — these are real paths, but they do not construct a
//   TurnOutcome.Failure anywhere in gateway main sources: VENDOR_RATE_LIMITED, VENDOR_QUOTA_EXHAUSTED,
//   AUTH_MISSING, AUTH_REFRESH_FAILED, POOL_EXHAUSTED, ADMISSION_FULL, REQUEST_TOO_LARGE. Rate
//   limiting, auth refresh and pool selection are decided in the retry loop and leave it as a thrown
//   UpstreamFailed, and admission/oversize are rejected before a turn exists. They are kept because
//   the retry matrix must still dispose of them, but a reader looking for their call sites will not
//   find one, and the disposition owes that path an explanation rather than a site.
//
//   PRODUCED BY A SITE AND MISSING FROM THE BRIEF — MODEL_REFUSED (ResponsesTerminalDecision:70,
//   ChatTerminalState:67), CONTENT_FILTERED (ResponsesTerminalDecision:96, ChatTerminalState:80) and
//   CODE_MODE_PROTOCOL (the five codex code-mode sites). A refusal and a content filter are the two
//   cases where the model answered and the answer was a verdict; folding either into a generic
//   upstream cause is what made a deterministic refusal look retryable, which is the defect V4-122
//   item 11 had to fix at the argument level. Reported to the orchestrator per the premise-check
//   duty: the brief's list is short by three and long by seven.
//
// WHAT THIS TYPE IS NOT: it does not carry the wire type. The brief is explicit that ErrorType is
// chosen BY the matrix from (cause, phase), never hand-picked at the site — the whole point is that
// the two axes stop being one field.
package splice.core.turn

/**
 * Why a turn failed — the truth about the turn, independent of what the client is told.
 *
 * Every value names, in this file, the site(s) that produce it, so a value with no producer is
 * visible as a value with no comment rather than as a silent hole.
 */
public enum class FailureCause {
    // PassthroughStreamTranslator:154, ChatStreamTranslator:122, ResponsesTerminalDecision:139 —
    // the watchdog fired while the upstream was silent. The tier is the phase, and it matters: an
    // idle tear mid-output carries salvage and can be resumed, a whole-turn cap can not.
    UPSTREAM_STALLED,

    // PassthroughStreamTranslator:188, ChatStreamTranslator:135, ResponsesTerminalDecision:113 —
    // the stream ended with no terminal event. The model may have said a great deal first; this is
    // the "resumed from delivered text" case, not the "nothing arrived" case.
    UPSTREAM_TRUNCATED,

    // SseRoundDriver:120 — the connection failed outright. Retried at L1 with backoff, which is a
    // different layer from resuming content that was already delivered.
    UPSTREAM_CONN_RESET,

    // PassthroughTerminalState:96, ChatTerminalState:60, ResponsesTerminalDecision:41 — a
    // response.failed/error the backend itself sent, carrying its own status. Split by status class
    // because a 5xx is retried and a 4xx generally is not, and that split is the matrix's to make.
    UPSTREAM_STATUS_5XX,
    UPSTREAM_STATUS_4XX,

    // The case the brief did not name, added on the 2026-09-18 ruling: the upstream reported the
    // failure IN-BAND — an SSE error event or a response.failed — and there is no HTTP status at
    // all, which is the normal shape on the streaming path (ResponsesEventReducer.kt:103 and
    // ZeroEventFailure.kt:38 both call classify with no status). Deliberately NOT folded into 5XX:
    // that would make the wire claim "server error" for something the vendor called by another
    // name. The vendor's own type string rides in the message, which is what lets the visible text
    // name it; the matrix retries it on the generic bounded curve unless that string matches a
    // known terminal entry.
    UPSTREAM_REPORTED,

    // ResponsesTerminalDecision:70, ChatTerminalState:67. The model answered, and its answer was
    // "no". Deterministic: the identical prompt is refused identically, so a retry buys the same
    // verdict at full price. This is the cause V4-122 item 11 found defaulting to retryable.
    MODEL_REFUSED,

    // ResponsesTerminalDecision:96, ChatTerminalState:80. Same determinism as a refusal, reached
    // by a different route.
    CONTENT_FILTERED,

    // PassthroughTerminalState:79, ChatTerminalState:51 and :54, ResponsesTerminalDecision:27 and
    // :32 — the tool-call arguments are corrupt or the runaway valve tripped. Our side of the wire
    // is what is damaged here, so it is not the upstream's verdict to re-earn.
    TOOL_TEAR,

    // RoundStrategy:102 — the backend asked for a custom tool call this dialect cannot express.
    DIALECT_UNSUPPORTED,

    // The five codex code-mode sites: CodeModePersistenceException:10, CodexCodeModeDriver:206,
    // CodexCodeModeMachine:48 and :69, CodexCodeModeResume:164, CodexCodeModeTurn:153. One cause
    // because they are one machine failing at different steps, and no layer below can repair a
    // protocol state error — which is exactly why the recovery differs from every upstream cause.
    CODE_MODE_PROTOCOL,

    // The floor. A failure splice cannot attribute to anything more specific says so rather than
    // borrowing a cause it has not earned; the retry default stays TOTAL on this value.
    INTERNAL,

    // No site constructs these (see the header). Kept so the matrix can dispose of the paths that
    // end a turn without building a TurnOutcome.Failure.
    VENDOR_RATE_LIMITED,
    VENDOR_QUOTA_EXHAUSTED,
    AUTH_MISSING,
    AUTH_REFRESH_FAILED,
    POOL_EXHAUSTED,
    ADMISSION_FULL,
    REQUEST_TOO_LARGE,
}

/**
 * Where in a turn's life the failure landed. Orthogonal to [FailureCause] on purpose — the same
 * cause means different things before and after the client has seen a byte, and the brief requires
 * the matrix to be keyed on the PAIR. Whether content already reached the client is what decides
 * which layers are still legal (a re-issue is safe before the first frame and a duplicate-output
 * risk after it).
 */
public enum class FailurePhase {
    /** Before the upstream connection is established. */
    CONNECT,

    /** Connected, but nothing has been handed to the client yet. */
    FIRST_BYTE,

    /** Content has already been streamed, so any repair has to continue from what was sent. */
    MID_OUTPUT,

    /** The turn had a terminal; the failure is the verdict rather than the interruption. */
    TERMINAL,
}
