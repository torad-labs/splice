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
// the pre-content rule. A failure whose base class is api_error is wired as overloaded_error
// whenever it arrives before the client has seen a byte, which is V4-78, relocated. It rested on
// the premise that the client re-sends an api_error only once it reads as overloaded; the
// 2026-10-04 measurement in the RETRY DEFAULT note below shows the client re-sends an api_error
// before content and after it, so the rule stands unchanged but now moves the label, not the
// retry. It used to live in the
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
internal object WireType {

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
        // A refusal and a filtered generation are the vendor's verdict on the REQUEST, the class
        // the vendor itself returns as HTTP 400 when it refuses before the stream. As api_error
        // they were a storm at every phase: at a pre-content phase the rule below made them
        // overloaded_error, and at a later one the client re-sent the api_error itself within a
        // second, after content frames as well. Of three such identical re-sends on 2026-10-04,
        // two met the same refusal and one was served; one 1,234,161-byte request was refused five
        // times from 6:05 to 6:07 PM CT. `permanent` never reached the first choice, since this
        // table and its rule run before the emitter reads it.
        FailureCause.MODEL_REFUSED to ErrorType.INVALID_REQUEST,
        FailureCause.CONTENT_FILTERED to ErrorType.INVALID_REQUEST,
        FailureCause.TOOL_TEAR to ErrorType.API_ERROR,
        FailureCause.DIALECT_UNSUPPORTED to ErrorType.INVALID_REQUEST,
        // INVALID_REQUEST, decided from the MATRIX rather than from the type the old sites happened
        // to pass: RetryMatrix gives CODE_MODE_PROTOCOL an EMPTY ceiling — no layer can repair local
        // protocol state — so wiring it api_error would advertise a retry (api_error becomes
        // overloaded_error pre-content) that the matrix says cannot help. The non-retryable class is
        // the honest one, and it is what the codex module predominantly passed before.
        FailureCause.CODE_MODE_PROTOCOL to ErrorType.INVALID_REQUEST,
        // RETRY DEFAULT IS TOTAL, and this line is where the law is enforced rather than described:
        // a failure splice could not attribute is wired OVERLOADED, a class Claude Code re-sends on
        // its own, and never INVALID_REQUEST, the in-band class it rarely re-sends. This note used to say
        // API_ERROR is retried only before content. MEASURED 2026-10-04, it is retried before and
        // after: in the perf rows of the claudex, claude-splice and claude-muse heads, Sep 15 to
        // Oct 4, the same session sent a request of identical size within 2 s of the turn's end after
        // 7 of 12 api_error turns, 86 of 171 overloaded_error turns, 3 of 50 invalid_request_error
        // turns and 5 of 232,628 ok turns. That premise is why INTERNAL is OVERLOADED rather than
        // API_ERROR; the choice stands unchanged, and the client would re-send either. A dialect's
        // generic path caught the original case: it is exactly where splice cannot name the cause,
        // and the whole point of the default is that such a failure is still offered to the client.
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
// capacity problem is. The client re-sends an api_error too (MEASURED 2026-10-04, the RETRY
// DEFAULT note above), so this set moves the label, not whether a retry happens.
private val PRE_CONTENT: Set<FailurePhase> = setOf(FailurePhase.CONNECT, FailurePhase.FIRST_BYTE)
