// NEW: failure-handling slice 1 (2026-10-26). What the client is told about a failure, chosen in one place.
//
// Three tables used to choose it between them. WireType gave a failure its type from the old cause enum,
// SseEmitter's PreContentWireType relabelled that type before content, and CollectingTerminal.statusFor gave a
// stream=false answer its status; AdmissionResponses spelled each refusal's status by hand. This object answers all of
// it from the leaf and where the turn stood.
//
// Failure-handling slice 4: the R, P and T families answer with what the client reaction record shows Claude Code
// 2.1.310 does (tools/e2e/fixtures/client-reactions/2.1.310.toml, read by ClientWireRecordTest), and only the login
// causes take a login status (final-design.md section 11).
package splice.core.failure

import splice.core.failure.policy.FailureFamilies
import splice.core.turn.ErrorType
import splice.core.wire.HttpStatus
import java.time.Instant

/** The client's answer to a failure: the HTTP [status] while the answer is still a status (null once the stream is
 *  open, where the failure is an in-band error event), the error [type] the client reads, and the x-should-retry
 *  header, null when it is not sent. [afterContent]: the error follows model content the client already shows. */
public data class WireAnswer(
    val status: Int?,
    val type: ErrorType,
    val shouldRetry: Boolean?,
    val afterContent: Boolean = false,
    /** The held wait handed to the client, with the reset and window its next request resumes. */
    val handoff: Handoff? = null,
) {
    public data class Handoff(
        val resetsAt: Instant? = null,
        val recheck: Boolean = false,
        val window: LimitWindow? = null,
    )

    /** Family L keeps its true reset beside a pooled head's earlier recheck. */
    public val resetsAt: Instant? get() = handoff?.resetsAt
    public val recheck: Boolean get() = handoff?.recheck == true
    public val window: LimitWindow? get() = handoff?.window
    public val handedOver: Boolean get() = handoff != null

    /** Whether Claude Code sends this answer again, from the 2.1.310 reaction record. In band it re-sends an overload
     *  before content only. A 429 is re-sent even with x-should-retry false; a 403 and 413 are not. */
    public val clientResends: Boolean
        get() = when {
            status == null -> type == ErrorType.OVERLOADED && !afterContent
            shouldRetry == false -> status == HttpStatus.TOO_MANY_REQUESTS
            else -> status != HttpStatus.FORBIDDEN && status != HttpStatus.CONTENT_TOO_LARGE
        }
}

/** How soon a client told family L comes back where another login could serve the head: ClientRecheck's bound, which
 *  the L row keeps (final-design.md section 9, "When a hold does not fit"). */
public const val RECHECK_SECONDS: Long = 30L

public object ClientWire {
    /** How Claude Code acts on an ending (final-design.md section 11). [KEPT] answers as before slice 4, through [Kept]:
     *  a held turn's ending, whose family is slice 5's; a request too slow to reach splice, which keeps its own 408; and
     *  the refusal of a session's own splice key at the door, which keeps its 401 with no x-should-retry, since 2.1.310
     *  answers a 401 by re-reading its key helper and sending what it returns (http-401) — the recovery a rotated key
     *  needs. */
    internal enum class Family { R, P, CLIENT_LOGIN, HELD_LOGIN, T, H, L, KEPT }

    /** P's status, where a re-send of the same bytes meets the same verdict: x-should-retry false stops the client
     *  (http-400-should-retry-false), and the line prints with no login label in front. T takes it too, and its line
     *  says the words Claude Code compacts on. */
    private val STOPPED = WireAnswer(HttpStatus.BAD_REQUEST, ErrorType.INVALID_REQUEST, false)

    /** The provider refused the person's own forwarded login: a 401 authentication_error, which the client reads as
     *  its own login failing, and x-should-retry false keeps it from sending the same login again first. */
    private val CLIENT_LOGIN_REFUSED = WireAnswer(HttpStatus.UNAUTHORIZED, ErrorType.AUTHENTICATION, false)

    /** A login splice holds is refused or missing: a 403 permission_error, which 2.1.310 does not re-send and on which
     *  the record found no login flow of the client's own (http-403). */
    private val HELD_LOGIN_REFUSED = WireAnswer(HttpStatus.FORBIDDEN, ErrorType.PERMISSION, false)

    /** R's status: Claude Code sends a 529 again, streamed or not, without end (http-529-stream,
     *  http-529-non-stream). A limit and a spent account keep today's 429, which it sends again too. */
    private val RESEND = WireAnswer(HttpStatus.OVERLOADED, ErrorType.OVERLOADED, null)
    private val RESEND_LIMITED = WireAnswer(HttpStatus.TOO_MANY_REQUESTS, ErrorType.RATE_LIMIT, null)

    /** A limit the session cannot wait out, handed to the client (family L): a 429 with no x-should-retry, which
     *  Claude Code sleeps on until the time it names and then sends again (http-429-retry-after-30s,
     *  http-429-reset-hours-away). */
    private val HANDED_OVER = WireAnswer(HttpStatus.TOO_MANY_REQUESTS, ErrorType.RATE_LIMIT, null)

    /** What the client is told about [cause] at [at]. [repeated]: the request's identical predecessor already ended R
     *  (RequestFacts.repeated, the ResendLedger), so an R ending is told as P. [otherLogin]: the head's pool holds
     *  another login, so an L answer names a time [RECHECK_SECONDS] away rather than the true reset. */
    public fun answer(
        cause: FailureCause,
        at: Position,
        repeated: Boolean = false,
        otherLogin: Boolean = false,
    ): WireAnswer {
        val family = family(cause, repeated)
        if (family == ClientWire.Family.KEPT) return Kept.answer(cause, at)
        val told = when (at.moment) {
            Moment.ADMISSION, Moment.BEFORE_COMMIT, Moment.NON_STREAM ->
                if (family == ClientWire.Family.L) handedOver(cause, otherLogin) else status(cause, family, at)
            Moment.COMMITTED -> inBand(committedType(cause, family))
            Moment.MID_CONTENT -> afterContent(cause, repeated)
        }
        return if (family == ClientWire.Family.L) told.copy(handoff = told.handoff ?: WireAnswer.Handoff()) else told
    }

    /** L's status, with the reset it names: the held cause's true reset, or a recheck where another login could serve
     *  the head. */
    private fun handedOver(cause: FailureCause, otherLogin: Boolean): WireAnswer =
        (cause as? FailureCause.Splice.HoldOutlasted).let { outlasted ->
            HANDED_OVER.copy(
                handoff = WireAnswer.Handoff(
                    resetsAt = outlasted?.nextReset,
                    recheck = otherLogin,
                    window = (outlasted?.held as? FailureCause.Provider.Limited)?.window,
                ),
            )
        }

    /** The type [cause] reads as once content reached the client, and in a stream=false body. */
    public fun ownType(cause: FailureCause): ErrorType = OwnTypes.of(cause)

    private fun family(cause: FailureCause, repeated: Boolean): ClientWire.Family {
        val family = FailureFamilies.of(cause)
        return if (repeated && family == ClientWire.Family.R) ClientWire.Family.P else family
    }

    /** While the status is splice's to choose. */
    private fun status(cause: FailureCause, family: ClientWire.Family, at: Position): WireAnswer = when (family) {
        ClientWire.Family.R -> resend(cause, at)
        // A request too large keeps its 413 wherever it is found, which 2.1.310 does not send again (http-413).
        ClientWire.Family.P -> if (tooLarge(cause)) Kept.refusal(cause) else STOPPED
        // H stops on a limit too far to wait for. The record shows 2.1.310 sends a 429 again even with x-should-retry
        // false (http-429-should-retry-false), so H takes P's status and its line names the true reset.
        ClientWire.Family.T, ClientWire.Family.H -> STOPPED
        ClientWire.Family.L -> handedOver(cause, otherLogin = false)
        ClientWire.Family.CLIENT_LOGIN -> CLIENT_LOGIN_REFUSED
        ClientWire.Family.HELD_LOGIN -> HELD_LOGIN_REFUSED
        ClientWire.Family.KEPT -> Kept.answer(cause, at)
    }

    /** R before commit is today's eager preamble: the stream opens and an overloaded_error follows, which the client
     *  sends again (inband-overloaded-before-content). At admission and without streaming it is a status. */
    private fun resend(cause: FailureCause, at: Position): WireAnswer = when {
        at.moment == Moment.BEFORE_COMMIT -> inBand(ErrorType.OVERLOADED)
        ownType(cause) == ErrorType.RATE_LIMIT -> RESEND_LIMITED
        else -> RESEND
    }

    /** Committed with only splice's notice shown: overloaded_error is sent again after a notice, and
     *  invalid_request_error is printed and not (inband-signed-notice). The client's own refused login keeps
     *  authentication_error, which is never remapped. */
    private fun committedType(cause: FailureCause, family: ClientWire.Family): ErrorType = when (family) {
        // L in band is an overload the client sends again at once, and the re-send meets L's 429 before commit.
        ClientWire.Family.R, ClientWire.Family.L -> ErrorType.OVERLOADED
        ClientWire.Family.CLIENT_LOGIN -> ErrorType.AUTHENTICATION
        ClientWire.Family.P, ClientWire.Family.T, ClientWire.Family.H,
        ClientWire.Family.HELD_LOGIN,
        -> ErrorType.INVALID_REQUEST
        ClientWire.Family.KEPT -> Kept.answer(cause, Position(Moment.COMMITTED, streamBegan = true)).type
    }

    /** After content the type is the cause's own: the client keeps what it holds and sends nothing again
     *  (inband-*-after-content). The one exception is a [repeated] request whose own type is overloaded_error, which
     *  the client sends once more after thinking alone: it reads invalid_request_error, so the re-send loop stops. */
    private fun afterContent(cause: FailureCause, repeated: Boolean): WireAnswer {
        val own = ownType(cause)
        val type = if (repeated && own == ErrorType.OVERLOADED) ErrorType.INVALID_REQUEST else own
        return WireAnswer(null, type, null, afterContent = true)
    }

    private fun tooLarge(cause: FailureCause): Boolean =
        (cause as? FailureCause.Splice.Declined)?.why.let { it is Decline.TooLarge || it is Decline.Unfit }

    private fun inBand(type: ErrorType): WireAnswer = WireAnswer(null, type, null)
}

/** The answers slice 4 keeps as they were: a refusal at admission by its own status, in band before content with a
 *  retryable type relabelled as an overload, the cause's own type after content, and its type's status without
 *  streaming. A held turn's ending (slice 5), a request too slow to arrive, the caller's own credential at splice's
 *  door, and a request too large, whose 413 the record shows is not re-sent, are answered here. */
private object Kept {
    fun answer(cause: FailureCause, at: Position): WireAnswer = when (at.moment) {
        Moment.ADMISSION -> refusal(cause)
        Moment.BEFORE_COMMIT, Moment.COMMITTED -> WireAnswer(null, beforeContent(cause), null)
        Moment.MID_CONTENT -> WireAnswer(null, ClientWire.ownType(cause), null, afterContent = true)
        Moment.NON_STREAM -> ClientWire.ownType(cause).let { WireAnswer(statusOf(it), it, null) }
    }

    /** Before content (V4-81), for a held turn's ending: a rate limit reads as an overload, which the client
     *  re-sends, and so does an api_error unless a re-send reproduces it. */
    private fun beforeContent(cause: FailureCause): ErrorType {
        val own = ClientWire.ownType(cause)
        val repeats = cause.permanent || cause.deterministic
        val retryable = own == ErrorType.RATE_LIMIT || (own == ErrorType.API_ERROR && !repeats)
        return if (retryable) ErrorType.OVERLOADED else own
    }

    /** A refusal before any turn exists keeps its real type: the status owns the client's retry, not the SSE rule. */
    fun refusal(cause: FailureCause): WireAnswer {
        val own = ClientWire.ownType(cause)
        val why = (cause as? FailureCause.Splice.Declined)?.why
        return WireAnswer(why?.let(::refusalStatus) ?: statusOf(own), own, why?.let(::refusalRetry))
    }

    /** The refusals whose status is not their type's own: a request too large is a 413, which 2.1.310 does not send
     *  again (http-413), and one too slow to arrive a 408, which it does (http-408). */
    private fun refusalStatus(why: Decline): Int? = when (why) {
        is Decline.TooLarge, is Decline.Unfit -> HttpStatus.CONTENT_TOO_LARGE
        Decline.ReadTimeout -> HttpStatus.REQUEST_TIMEOUT
        Decline.NoSharedLogin, Decline.HeapBusy, is Decline.Budget, is Decline.Preflight, is Decline.BadBody,
        is Decline.UnknownModel, Decline.ForeignHost, Decline.BadLocalKey, is Decline.SpliceKeyAsLogin,
        Decline.LivenessProbe, is Decline.Untranslatable,
        -> null
    }

    /** x-should-retry false where a re-send of the same bytes meets the same refusal: the compaction preflight's size
     *  bound, which the client answers by compacting. */
    private fun refusalRetry(why: Decline): Boolean? = when (why) {
        is Decline.Preflight -> false
        Decline.NoSharedLogin, is Decline.TooLarge, is Decline.Unfit, Decline.HeapBusy, is Decline.Budget,
        is Decline.BadBody, Decline.ReadTimeout, is Decline.UnknownModel, Decline.ForeignHost, Decline.BadLocalKey,
        is Decline.SpliceKeyAsLogin, Decline.LivenessProbe, is Decline.Untranslatable,
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

/** Each leaf's family (final-design.md section 11). A limit and a spent account end through a hold step, which keeps
 *  today's R wire until slice 5 brings holds; a cut round never ends a turn, and reads as R were it asked. */

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
        is FailureCause.Provider.Overloaded, is FailureCause.Provider.Empty, is FailureCause.Provider.ReasoningCut,
        -> ErrorType.OVERLOADED
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

    /** Slice 4: no refusal but a login cause reads as a login, so a budget, a foreign host and a shared turn with no
     *  login here print with no login label (final-design.md section 11, ruling 4). The splice key a session sent
     *  where its provider login belongs stays authentication_error: that credential is the one being refused. */
    private fun declined(why: Decline): ErrorType = when (why) {
        Decline.HeapBusy -> ErrorType.OVERLOADED
        Decline.BadLocalKey, is Decline.SpliceKeyAsLogin -> ErrorType.AUTHENTICATION
        is Decline.TooLarge, is Decline.Unfit, is Decline.Preflight, is Decline.BadBody, Decline.ReadTimeout,
        is Decline.UnknownModel, Decline.LivenessProbe, is Decline.Untranslatable, is Decline.Budget,
        Decline.ForeignHost, Decline.NoSharedLogin,
        -> ErrorType.INVALID_REQUEST
    }
}
