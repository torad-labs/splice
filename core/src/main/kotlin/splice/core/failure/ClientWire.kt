// NEW: failure-handling slice 1 (2026-10-26). What the client is told about a failure, chosen in one place.
//
// Three tables used to choose it between them. WireType gave a failure its type from the old cause enum,
// SseEmitter's PreContentWireType relabelled that type before content, and CollectingTerminal.statusFor gave a
// stream=false answer its status; AdmissionResponses spelled each refusal's status by hand. This object answers all of
// it from the leaf and where the turn stood. Slice 1 encodes today's answers exactly, which
// ClientWireTodayPropertyTest pins over every leaf and position; a later slice that changes what the client is told
// changes it here and nowhere else.
package splice.core.failure

import splice.core.turn.ErrorType
import splice.core.wire.HttpStatus

/** The client's answer to a failure: the HTTP [status] while the answer is still a status (null once the stream is
 *  open, where the failure is an in-band error event), the error [type] the client reads, and the x-should-retry
 *  header, null when it is not sent. */
public data class WireAnswer(val status: Int?, val type: ErrorType, val shouldRetry: Boolean?)

public object ClientWire {
    /** An overflow found while the turn's status was still splice's to choose: Claude Code compacts on a 400 that
     *  says the prompt is too long, and x-should-retry false keeps it from re-sending the same bytes first. */
    private val OVERFLOW_BEFORE_COMMIT = WireAnswer(HttpStatus.BAD_REQUEST, ErrorType.INVALID_REQUEST, false)

    /** What the client is told about [cause] at [at]. */
    public fun answer(cause: FailureCause, at: Position): WireAnswer = when (at.moment) {
        Moment.ADMISSION -> refusal(cause)
        Moment.BEFORE_COMMIT ->
            if (cause is FailureCause.Provider.TooLong) OVERFLOW_BEFORE_COMMIT else inBand(beforeContent(cause))
        Moment.COMMITTED -> inBand(beforeContent(cause))
        Moment.MID_CONTENT -> inBand(ownType(cause))
        Moment.NON_STREAM -> ownType(cause).let { WireAnswer(statusOf(it), it, null) }
    }

    /** The type [cause] reads as once content reached the client, and in a stream=false body. */
    public fun ownType(cause: FailureCause): ErrorType = OwnTypes.of(cause)

    /** Before content (V4-81): a rate limit always reads as an overload, which the client re-sends, and so does an
     *  api_error unless a re-send reproduces it. Every other type is already the one the client should read.
     *
     *  The rule was written on the premise that Claude Code 2.1.257 retries an in-band error event only when its body
     *  carries overloaded_error (a real 429/529 is retried by status, and neither is splice's to send once the 200 is
     *  committed). Measured 2026-10-04, that premise is false for api_error: in the perf rows of the claudex,
     *  claude-splice and claude-muse heads, Sep 15 to Oct 4, the same session sent a request of identical size within
     *  2 s of the turn's end after 7 of 12 api_error turns, 86 of 171 overloaded_error turns, 3 of 50
     *  invalid_request_error turns and 5 of 232,628 ok turns, before content and after it. invalid_request_error is
     *  the in-band class the client rarely re-sends. Those rows hold no in-band rate_limit_error, so for a rate limit
     *  the premise is unmeasured, not disproved.
     *
     *  A rate limit stays retryable however the classifier scored it, because a quota window changes with time:
     *  V4-71's fix is that the first persistent 429 reaches the client as something it re-sends, into the cooldown
     *  RetryPolicy arms. "Always a retry armed" is about failures a retry can heal, and a permanent or deterministic
     *  failure is not one: identical bytes earn the identical verdict, and with CLAUDE_CODE_RETRY_WATCHDOG=1 a
     *  relabelled one costs up to 300 client re-sends at four upstream attempts each, so it keeps its real type.
     *  After content the type is left alone, since the client finalizes what it holds; INVALID_REQUEST and
     *  AUTHENTICATION are never remapped; and only the wire type moves, never the message or the recorded type. */
    private fun beforeContent(cause: FailureCause): ErrorType {
        val own = ownType(cause)
        val repeats = cause.permanent || cause.deterministic
        val retryable = own == ErrorType.RATE_LIMIT || (own == ErrorType.API_ERROR && !repeats)
        return if (retryable) ErrorType.OVERLOADED else own
    }

    private fun inBand(type: ErrorType): WireAnswer = WireAnswer(null, type, null)

    /** A refusal before any turn exists keeps its real type: the status owns the client's retry, not the SSE rule. */
    private fun refusal(cause: FailureCause): WireAnswer {
        val own = ownType(cause)
        val why = (cause as? FailureCause.Splice.Declined)?.why
        return WireAnswer(why?.let(::refusalStatus) ?: statusOf(own), own, why?.let(::refusalRetry))
    }

    /** The three refusals whose status is not their type's own. */
    private fun refusalStatus(why: Decline): Int? = when (why) {
        is Decline.TooLarge -> HttpStatus.CONTENT_TOO_LARGE
        Decline.ReadTimeout -> HttpStatus.REQUEST_TIMEOUT
        // A login coming back to this head is this machine's to do, so the node shows the sentence at once.
        Decline.NoSharedLogin -> HttpStatus.SERVICE_UNAVAILABLE
        Decline.HeapBusy, Decline.Budget, is Decline.Preflight, is Decline.BadBody, is Decline.UnknownModel,
        Decline.ForeignHost, Decline.BadLocalKey, Decline.LivenessProbe, is Decline.Untranslatable,
        -> null
    }

    /** x-should-retry false where a re-send of the same bytes meets the same refusal: the compaction preflight's size
     *  bound, which the client answers by compacting, and a shared turn with no login here. */
    private fun refusalRetry(why: Decline): Boolean? = when (why) {
        is Decline.Preflight, Decline.NoSharedLogin -> false
        is Decline.TooLarge, Decline.HeapBusy, Decline.Budget, is Decline.BadBody, Decline.ReadTimeout,
        is Decline.UnknownModel, Decline.ForeignHost, Decline.BadLocalKey, Decline.LivenessProbe,
        is Decline.Untranslatable,
        -> null
    }

    /** The status a type answers with when the answer is a status: the Anthropic conventions, with api_error as
     *  502 to match the upstream-error and empty-model answer of a stream=false request. */
    private fun statusOf(type: ErrorType): Int = when (type) {
        ErrorType.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
        ErrorType.AUTHENTICATION -> HttpStatus.UNAUTHORIZED
        ErrorType.PERMISSION -> HttpStatus.FORBIDDEN
        ErrorType.NOT_FOUND -> HttpStatus.NOT_FOUND
        ErrorType.RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS
        ErrorType.OVERLOADED -> HttpStatus.OVERLOADED
        ErrorType.API_ERROR -> HttpStatus.BAD_GATEWAY
    }
}

/** Each leaf's own type: what the site that decides it wrote before slice 1. */
private object OwnTypes {
    fun of(cause: FailureCause): ErrorType = when (cause) {
        is FailureCause.Provider -> provider(cause)
        is FailureCause.Path -> path(cause)
        is FailureCause.Splice -> splice(cause)
    }

    private fun provider(cause: FailureCause.Provider): ErrorType = when (cause) {
        is FailureCause.Provider.Limited, is FailureCause.Provider.NeedsPayment -> ErrorType.RATE_LIMIT
        // An empty answer is indistinguishable from a transient overload, and nothing reached the client (V4-42).
        is FailureCause.Provider.Overloaded, is FailureCause.Provider.Empty -> ErrorType.OVERLOADED
        is FailureCause.Provider.ServerError, is FailureCause.Provider.ToolTear, is FailureCause.Provider.Unrecognized,
        -> ErrorType.API_ERROR
        // The provider's verdict on the request: identical bytes earn it again.
        is FailureCause.Provider.Forbidden, is FailureCause.Provider.Rejected, is FailureCause.Provider.ModelMissing,
        is FailureCause.Provider.TooLong, is FailureCause.Provider.Refused,
        -> ErrorType.INVALID_REQUEST
        is FailureCause.Provider.AuthRejected -> ErrorType.AUTHENTICATION
    }

    /** A broken path is an overload the client re-sends, except an event too large to read, which was the
     *  provider's to send. */
    private fun path(cause: FailureCause.Path): ErrorType = when (cause) {
        is FailureCause.Path.Torn ->
            if (cause.why == Tear.OVERSIZED_EVENT) ErrorType.API_ERROR else ErrorType.OVERLOADED
        is FailureCause.Path.Unreachable, is FailureCause.Path.Stalled -> ErrorType.OVERLOADED
    }

    private fun splice(cause: FailureCause.Splice): ErrorType = when (cause) {
        is FailureCause.Splice.NoLogin -> ErrorType.AUTHENTICATION
        is FailureCause.Splice.AtCapacity, is FailureCause.Splice.TurnBudgetSpent,
        FailureCause.Splice.CompactionUnfinished,
        -> ErrorType.OVERLOADED
        is FailureCause.Splice.Declined -> declined(cause.why)
        is FailureCause.Splice.HoldOutlasted -> of(cause.held)
        is FailureCause.Splice.Stopped -> stopped(cause.by)
        is FailureCause.Splice.Protocol -> ErrorType.INVALID_REQUEST
        is FailureCause.Splice.Internal -> internal(cause.escaped)
    }

    /** The console's stop is the operator's verdict on this request; a restart's and a stopping head's are not. */
    private fun stopped(by: StoppedBy): ErrorType = when (by) {
        StoppedBy.CONSOLE -> ErrorType.INVALID_REQUEST
        StoppedBy.RESTART, StoppedBy.HEAD_STOPPING -> ErrorType.OVERLOADED
    }

    /** An exception that escaped the turn's own handling answers api_error; any other unattributed failure is
     *  offered back to the client as an overload, since retry default is total. */
    private fun internal(escaped: Boolean): ErrorType = if (escaped) ErrorType.API_ERROR else ErrorType.OVERLOADED

    private fun declined(why: Decline): ErrorType = when (why) {
        Decline.HeapBusy -> ErrorType.OVERLOADED
        Decline.Budget, Decline.ForeignHost -> ErrorType.PERMISSION
        Decline.BadLocalKey -> ErrorType.AUTHENTICATION
        Decline.NoSharedLogin -> ErrorType.API_ERROR
        is Decline.TooLarge, is Decline.Preflight, is Decline.BadBody, Decline.ReadTimeout, is Decline.UnknownModel,
        Decline.LivenessProbe, is Decline.Untranslatable,
        -> ErrorType.INVALID_REQUEST
    }
}
