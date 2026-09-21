// NEW: V4-117 (error taxonomy, 2026-09-18) — the retry class the client keys on, chosen from
// (cause, phase) so that NOBODY hand-picks it at a failure site.
//
// WHY IT LIVES IN CORE AND NOT BESIDE THE LAYERS MATRIX: the layers matrix is in :upstream
// because its consumer (the retry loop) is there, but the WIRE TYPE has to be readable by
// TurnOutcome.Failure itself, which is in core — and core cannot see :upstream (the dependency
// runs the other way). This is a pure function of two core enums and needs nothing from the layer
// ladder, so core is where it belongs, and :upstream's matrix delegates here rather than keeping
// a second copy that could drift.
//
// THE PHASE EARNS ITS PLACE IN THE SIGNATURE, and this is the one case where it changes the answer:
// the pre-content rule. Claude Code's own handling diverges on whether content has already reached
// the user, so a failure whose base class is api_error is wired as overloaded_error whenever it
// arrives before the client has seen a byte — which is V4-78, relocated. It used to live in the
// emitter as a relabel of one type into another, which meant the type had a second author at the
// boundary and the failure itself still claimed api_error. Here the boundary corrects the PHASE
// (it alone knows whether a frame went out) and the type follows from the pair, so the failure and
// the wire agree by construction.
//
// WHAT IS DELIBERATELY NOT PRE-CONTENT-SENSITIVE: the causes whose base is not api_error. A 4xx
// verdict on the request is invalid_request_error whether or not content was delivered, because
// the client's own retry is not what makes re-sending pointless — the request does.
package splice.core.turn

/** The retry class a failure is wired with, from what went wrong and how far the turn had got. */
public object WireType {

    /** The type the client keys its own retry behaviour on. See [PRE_CONTENT] for the one rule. */
    public fun of(cause: FailureCause, phase: FailurePhase): ErrorType {
        val base = BASE.getValue(cause)
        return if (base == ErrorType.API_ERROR && phase in PRE_CONTENT) ErrorType.OVERLOADED else base
    }

    /** The post-content class for each cause: what the client sees once bytes have reached it. */
    private val BASE: Map<FailureCause, ErrorType> = mapOf(
        FailureCause.UPSTREAM_STALLED to ErrorType.OVERLOADED,
        FailureCause.UPSTREAM_TRUNCATED to ErrorType.OVERLOADED,
        FailureCause.UPSTREAM_CONN_RESET to ErrorType.OVERLOADED,
        FailureCause.UPSTREAM_STATUS_5XX to ErrorType.OVERLOADED,
        // A 4xx is the upstream's verdict on the REQUEST: the client's bad-request class, which it
        // does not spin on, because identical bytes re-earn the identical answer.
        FailureCause.UPSTREAM_STATUS_4XX to ErrorType.INVALID_REQUEST,
        FailureCause.UPSTREAM_REPORTED to ErrorType.API_ERROR,
        // Refusals and filtered generations are api_error and PERMANENT. A type cannot carry that,
        // which is exactly why `permanent` is a separate field — and why V4-122 item 11 was a real
        // defect rather than a cosmetic one: the argument had been lost while the comment survived.
        FailureCause.MODEL_REFUSED to ErrorType.API_ERROR,
        FailureCause.CONTENT_FILTERED to ErrorType.API_ERROR,
        FailureCause.TOOL_TEAR to ErrorType.API_ERROR,
        FailureCause.DIALECT_UNSUPPORTED to ErrorType.INVALID_REQUEST,
        // INVALID_REQUEST, decided from the MATRIX rather than from the type the old sites happened
        // to pass: RetryMatrix gives CODE_MODE_PROTOCOL an EMPTY ceiling — no layer can repair local
        // protocol state — so wiring it api_error would advertise a retry (api_error becomes
        // overloaded_error pre-content) that the matrix says cannot help. The non-retryable class is
        // the honest one, and it is what the codex module predominantly passed before.
        FailureCause.CODE_MODE_PROTOCOL to ErrorType.INVALID_REQUEST,
        // RETRY DEFAULT IS TOTAL, and this line is where the law is enforced rather than described:
        // a failure splice could not attribute is wired OVERLOADED — the one class Claude Code
        // retries on its own — not API_ERROR. API_ERROR is only retryable PRE-content (see the
        // pre-content rule below), so an unattributable failure at MID_OUTPUT would have reached the
        // client as a non-retried ending. A dialect's generic path caught this: it is exactly the
        // case where splice cannot name the cause, and the whole point of the default is that such a
        // failure is still offered to the client rather than written off.
        FailureCause.INTERNAL to ErrorType.OVERLOADED,
        FailureCause.VENDOR_RATE_LIMITED to ErrorType.RATE_LIMIT,
        FailureCause.VENDOR_QUOTA_EXHAUSTED to ErrorType.RATE_LIMIT,
        FailureCause.AUTH_MISSING to ErrorType.AUTHENTICATION,
        FailureCause.AUTH_REFRESH_FAILED to ErrorType.AUTHENTICATION,
        FailureCause.POOL_EXHAUSTED to ErrorType.OVERLOADED,
        FailureCause.ADMISSION_FULL to ErrorType.OVERLOADED,
        FailureCause.REQUEST_TOO_LARGE to ErrorType.INVALID_REQUEST,
    )
}

// Nothing the client can see has happened yet, so a generic failure is advertised the way a
// capacity problem is — the one class Claude Code retries on its own.
private val PRE_CONTENT: Set<FailurePhase> = setOf(FailurePhase.CONNECT, FailurePhase.FIRST_BYTE)
