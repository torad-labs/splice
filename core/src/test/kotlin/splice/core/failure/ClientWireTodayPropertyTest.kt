// NEW: failure-handling slice 1. ClientWire must answer every failure exactly as today's three tables did, so the
// refactor that routes every ending through it moves no byte of the wire. The property runs over every leaf, every
// value of each leaf's fields, every moment and both stream states, and each expected value is read from today's code,
// never from a run:
//  - before content, RATE_LIMIT and a non-permanent API_ERROR are wired as OVERLOADED; INVALID_REQUEST,
//    AUTHENTICATION, PERMISSION and OVERLOADED keep their type; after content the type is left alone
//    (SseEmitter.kt:317-353, PreContentWireType);
//  - a stream=false answer carries its type's status, api_error as 502 (CollectingTerminal.kt:156-164);
//  - the admission plane's statuses (AdmissionResponses.kt:50-120, LocalRefusal.kt:97-126), and the overflow found
//    before the status went out, 400 with x-should-retry false (TurnStreamer.kt:209-213).
// The type each leaf's site wrote today is [todayType] below, one line per site, so the table is not ClientWire's own.
package splice.core.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.wire.HttpStatus
import java.time.Instant

/** Every lift a leaf can carry. */
private val LIFTS: List<Lift> = listOf(
    Lift.Unstated,
    Lift.At(Instant.parse("2026-10-26T18:00:00Z"), firm = true, source = LiftSource.RESET_HEADER),
    Lift.At(Instant.parse("2026-10-26T18:00:00Z"), firm = false, source = LiftSource.RETRY_AFTER),
    Lift.At(Instant.parse("2026-10-26T18:00:00Z"), firm = true, source = LiftSource.BODY),
)

private val BOOLS = listOf(false, true)

/** Every reason splice declines a request, each field case included. */
private val DECLINES: List<Decline> = listOf(
    Decline.TooLarge(bytes = 9_000_000L, limit = 8_000_000L),
    Decline.TooLarge(bytes = null, limit = 8_000_000L),
    Decline.HeapBusy,
    Decline.Budget(limit = "$20.00", resetsAt = Instant.parse("2026-10-27T05:00:00Z")),
    Decline.BadBody(readBytes = 12L, declaredBytes = 40L),
    Decline.BadBody(readBytes = 12L, declaredBytes = null),
    Decline.ReadTimeout,
    Decline.UnknownModel("not-served"),
    Decline.ForeignHost,
    Decline.BadLocalKey,
    Decline.LivenessProbe,
    Decline.NoSharedLogin,
) + PreflightKind.entries.map { Decline.Preflight(it, windowTokens = 262_144L) } +
    RequestFeature.entries.map(Decline::Untranslatable)

/** The provider leaves a lift rides on, with every lift. */
private fun liftedCauses(): List<FailureCause> = LIFTS.flatMap { lift ->
    (LimitWindow.entries + null).map { FailureCause.Provider.Limited(lift, it) } +
        BOOLS.map { FailureCause.Provider.Overloaded(lift, loading = it) } +
        BOOLS.map { FailureCause.Provider.NeedsPayment(lift, subscription = it) }
}

private fun providerCauses(): List<FailureCause> = buildList {
    addAll(liftedCauses())
    addAll(ServerFault.entries.map { FailureCause.Provider.ServerError(it) })
    add(FailureCause.Provider.Forbidden())
    addAll(LoginOwner.entries.map { FailureCause.Provider.AuthRejected(it) })
    addAll(
        listOf(null, 131_072L).flatMap { limit ->
            BOOLS.map { FailureCause.Provider.TooLong(limit, whileAnswering = it) }
        },
    )
    addAll(BOOLS.map { FailureCause.Provider.Rejected(staleReasoning = it) })
    add(FailureCause.Provider.ModelMissing("gone-model"))
    addAll(RefusedBy.entries.map { FailureCause.Provider.Refused(it) })
    addAll(BOOLS.map { FailureCause.Provider.Empty(reasoningOnly = it) })
    addAll(BOOLS.flatMap { runaway -> BOOLS.map { FailureCause.Provider.ToolTear(runaway, permanent = it) } })
    addAll(BOOLS.map { FailureCause.Provider.Unrecognized(clientError = it) })
}

private fun pathCauses(): List<FailureCause> = buildList {
    Unreach.entries.forEach { why -> BOOLS.forEach { add(FailureCause.Path.Unreachable(why, local = it)) } }
    Tear.entries.forEach { why -> BOOLS.forEach { add(FailureCause.Path.Torn(why, toolOpen = it)) } }
    Stall.entries.forEach { add(FailureCause.Path.Stalled(it)) }
}

private fun spliceCauses(): List<FailureCause> = buildList {
    listOf(null, "claude login").forEach { add(FailureCause.Splice.NoLogin(it)) }
    add(FailureCause.Splice.AtCapacity(limit = 4))
    DECLINES.forEach { add(FailureCause.Splice.Declined(it)) }
    BOOLS.forEach { add(FailureCause.Splice.TurnBudgetSpent(budgetMs = 900_000L, beforeFirstAttempt = it)) }
    add(FailureCause.Splice.CompactionUnfinished)
    StoppedBy.entries.forEach { add(FailureCause.Splice.Stopped(it)) }
    ProtocolFault.entries.forEach { add(FailureCause.Splice.Protocol(it)) }
    BOOLS.forEach { escaped -> BOOLS.forEach { add(FailureCause.Splice.Internal(escaped, permanent = it)) } }
}

/** Every leaf, every value of its fields; a held turn may outlast any of them. */
private val CAUSES: List<FailureCause> = (providerCauses() + pathCauses() + spliceCauses()).let { leaves ->
    leaves + Outlast.entries.flatMap { why ->
        leaves.map { held -> FailureCause.Splice.HoldOutlasted(why, held, Instant.parse("2026-10-27T00:00:00Z")) }
    }
}

private val POSITIONS: List<Position> = Moment.entries.flatMap { moment -> BOOLS.map { Position(moment, it) } }

/** The type today's site wrote for each leaf: the classifier's type for a provider's HTTP answer
 *  (UpstreamFailureClassifier), WireType's table for an in-band one, and each splice ending's own emitError. */
private fun todayType(cause: FailureCause): ErrorType = when (cause) {
    is FailureCause.Provider -> todayProviderType(cause)
    is FailureCause.Path -> todayPathType(cause)
    is FailureCause.Splice -> todaySpliceType(cause)
}

private fun todayProviderType(cause: FailureCause.Provider): ErrorType = when (cause) {
    // A 429 and a spent quota: WireType VENDOR_RATE_LIMITED, VENDOR_QUOTA_EXHAUSTED; the classifier's 429 floor.
    is FailureCause.Provider.Limited, is FailureCause.Provider.NeedsPayment -> ErrorType.RATE_LIMIT
    // The classifier's overload arm and WireType's UPSTREAM_STATUS_5XX for an in-band 5xx; StreamPromote's empty
    // model and empty compaction endings.
    is FailureCause.Provider.Overloaded, is FailureCause.Provider.Empty -> ErrorType.OVERLOADED
    // The classifier's 5xx floor on an HTTP answer, ForeignCredential.upstreamsOwn's API_ERROR, and WireType's
    // TOOL_TEAR and UPSTREAM_REPORTED.
    is FailureCause.Provider.ServerError, is FailureCause.Provider.ToolTear, is FailureCause.Provider.Unrecognized,
    -> ErrorType.API_ERROR
    // A 4xx, an overflow, a model refusal and a policy refusal: the request's verdict (WireType, the classifier).
    is FailureCause.Provider.Forbidden, is FailureCause.Provider.Rejected, is FailureCause.Provider.ModelMissing,
    is FailureCause.Provider.TooLong, is FailureCause.Provider.Refused,
    -> ErrorType.INVALID_REQUEST
    is FailureCause.Provider.AuthRejected -> ErrorType.AUTHENTICATION
}

private fun todayPathType(cause: FailureCause.Path): ErrorType = when (cause) {
    // WireType UPSTREAM_CONN_RESET and UPSTREAM_STALLED; TurnConnEnd's connection failure.
    is FailureCause.Path.Unreachable, is FailureCause.Path.Stalled -> ErrorType.OVERLOADED
    // TurnConnEnd.emitOversized writes api_error; every other tear is WireType's UPSTREAM_TRUNCATED.
    is FailureCause.Path.Torn -> if (cause.why == Tear.OVERSIZED_EVENT) ErrorType.API_ERROR else ErrorType.OVERLOADED
}

private fun todaySpliceType(cause: FailureCause.Splice): ErrorType = when (cause) {
    // TurnKnownEnd.emitAuthMissing.
    is FailureCause.Splice.NoLogin -> ErrorType.AUTHENTICATION
    // AdmissionResponses.respondAtCapacity; CancellationSeal's TotalCap; LocalResponses' unfinished compaction.
    is FailureCause.Splice.AtCapacity, is FailureCause.Splice.TurnBudgetSpent,
    FailureCause.Splice.CompactionUnfinished,
    -> ErrorType.OVERLOADED
    is FailureCause.Splice.Declined -> todayDeclinedType(cause.why)
    // A held turn's family is the cause it held on.
    is FailureCause.Splice.HoldOutlasted -> todayType(cause.held)
    // CancellationSeal: the console's stop is INVALID_REQUEST, a restart OVERLOADED; respondStopping's 529.
    is FailureCause.Splice.Stopped -> todayStoppedType(cause.by)
    // WireType CODE_MODE_PROTOCOL and DIALECT_UNSUPPORTED.
    is FailureCause.Splice.Protocol -> ErrorType.INVALID_REQUEST
    // TurnEnding's escaped exception is api_error; WireType INTERNAL and CancellationSeal's cancellation, overloaded.
    is FailureCause.Splice.Internal -> if (cause.escaped) ErrorType.API_ERROR else ErrorType.OVERLOADED
}

private fun todayStoppedType(by: StoppedBy): ErrorType =
    if (by == StoppedBy.CONSOLE) ErrorType.INVALID_REQUEST else ErrorType.OVERLOADED

/** AdmissionResponses' and LocalRefusal's error type for each refusal. */
private fun todayDeclinedType(why: Decline): ErrorType = when (why) {
    Decline.HeapBusy -> ErrorType.OVERLOADED
    is Decline.Budget, Decline.ForeignHost -> ErrorType.PERMISSION
    Decline.BadLocalKey -> ErrorType.AUTHENTICATION
    Decline.NoSharedLogin -> ErrorType.API_ERROR
    is Decline.TooLarge, is Decline.Preflight, is Decline.BadBody, Decline.ReadTimeout, is Decline.UnknownModel,
    Decline.LivenessProbe, is Decline.Untranslatable,
    -> ErrorType.INVALID_REQUEST
}

/** The permanence today's site passed to emitError with an api_error: the classifier's `!transient`, the dialect's
 *  own mark, or `permanent || deterministic` from the pipeline. Only an api_error's relabel reads it. */
private fun todayPermanent(cause: FailureCause): Boolean = when (cause) {
    is FailureCause.Provider -> todayProviderPermanent(cause)
    // No path failure is a provider's verdict: TurnConnEnd and the tear sites pass permanent = false.
    is FailureCause.Path -> false
    is FailureCause.Splice -> todaySplicePermanent(cause)
}

private fun todayProviderPermanent(cause: FailureCause.Provider): Boolean = when (cause) {
    // PassthroughTerminalState marks its runaway guard permanent; the chat and Responses guards do not.
    is FailureCause.Provider.ToolTear -> cause.permanent
    // The classifier's `!transient` (HTTP, Responses), the chat dialect's typed `!transient`.
    is FailureCause.Provider.Unrecognized -> cause.clientError
    // A 5xx and the foreign key are transient; every other leaf here is no api_error.
    is FailureCause.Provider.ServerError, is FailureCause.Provider.Limited, is FailureCause.Provider.Overloaded,
    is FailureCause.Provider.NeedsPayment, is FailureCause.Provider.Forbidden, is FailureCause.Provider.AuthRejected,
    is FailureCause.Provider.TooLong, is FailureCause.Provider.Rejected, is FailureCause.Provider.ModelMissing,
    is FailureCause.Provider.Refused, is FailureCause.Provider.Empty,
    -> false
}

private fun todaySplicePermanent(cause: FailureCause.Splice): Boolean = when (cause) {
    // TurnEnding: `permanent = e is URLParserException`.
    is FailureCause.Splice.Internal -> cause.permanent
    is FailureCause.Splice.HoldOutlasted -> todayPermanent(cause.held)
    is FailureCause.Splice.NoLogin, is FailureCause.Splice.AtCapacity, is FailureCause.Splice.Declined,
    is FailureCause.Splice.TurnBudgetSpent, FailureCause.Splice.CompactionUnfinished, is FailureCause.Splice.Stopped,
    is FailureCause.Splice.Protocol,
    -> false
}

/** PreContentWireType.of, before content. */
private fun todayBeforeContent(type: ErrorType, permanent: Boolean): ErrorType = when {
    type == ErrorType.RATE_LIMIT -> ErrorType.OVERLOADED
    type == ErrorType.API_ERROR && !permanent -> ErrorType.OVERLOADED
    else -> type
}

/** CollectingTerminal.statusFor. */
private fun todayStatus(type: ErrorType): Int = when (type) {
    ErrorType.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
    ErrorType.AUTHENTICATION -> HttpStatus.UNAUTHORIZED
    ErrorType.PERMISSION -> HttpStatus.FORBIDDEN
    ErrorType.NOT_FOUND -> HttpStatus.NOT_FOUND
    ErrorType.RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS
    ErrorType.OVERLOADED -> HttpStatus.OVERLOADED
    ErrorType.API_ERROR -> HttpStatus.BAD_GATEWAY
}

/** The admission plane's answer for each refusal it gives today, or null for a leaf it never answers. */
private fun todayAdmission(cause: FailureCause): WireAnswer? = when (cause) {
    // ExhaustedAccountAdmission.refuse answers through respondRateLimited.
    is FailureCause.Provider ->
        if (cause is FailureCause.Provider.Limited) {
            WireAnswer(HttpStatus.TOO_MANY_REQUESTS, ErrorType.RATE_LIMIT, null)
        } else {
            null
        }
    is FailureCause.Path -> null
    is FailureCause.Splice -> todaySpliceAdmission(cause)
}

private fun todaySpliceAdmission(cause: FailureCause.Splice): WireAnswer? = when (cause) {
    is FailureCause.Splice.AtCapacity -> WireAnswer(HttpStatus.OVERLOADED, ErrorType.OVERLOADED, null)
    is FailureCause.Splice.Stopped -> todayStoppedAdmission(cause.by)
    is FailureCause.Splice.Declined -> todayDeclined(cause.why)
    is FailureCause.Splice.NoLogin, is FailureCause.Splice.TurnBudgetSpent, is FailureCause.Splice.HoldOutlasted,
    FailureCause.Splice.CompactionUnfinished, is FailureCause.Splice.Protocol, is FailureCause.Splice.Internal,
    -> null
}

/** respondStopping's 529; TurnPreparation's refusal of a stopped turn's re-send goes through respondInvalidRequest. */
private fun todayStoppedAdmission(by: StoppedBy): WireAnswer? = when (by) {
    StoppedBy.HEAD_STOPPING -> WireAnswer(HttpStatus.OVERLOADED, ErrorType.OVERLOADED, null)
    StoppedBy.CONSOLE -> WireAnswer(HttpStatus.BAD_REQUEST, ErrorType.INVALID_REQUEST, null)
    StoppedBy.RESTART -> null
}

private fun todayDeclined(why: Decline): WireAnswer = when (why) {
    is Decline.TooLarge -> WireAnswer(HttpStatus.CONTENT_TOO_LARGE, ErrorType.INVALID_REQUEST, null)
    Decline.HeapBusy -> WireAnswer(HttpStatus.OVERLOADED, ErrorType.OVERLOADED, null)
    Decline.ReadTimeout -> WireAnswer(HttpStatus.REQUEST_TIMEOUT, ErrorType.INVALID_REQUEST, null)
    is Decline.Budget, Decline.ForeignHost -> WireAnswer(HttpStatus.FORBIDDEN, ErrorType.PERMISSION, null)
    Decline.BadLocalKey -> WireAnswer(HttpStatus.UNAUTHORIZED, ErrorType.AUTHENTICATION, null)
    // LocalRefusal.refuseIfOversized sets x-should-retry false before its 400.
    is Decline.Preflight -> WireAnswer(HttpStatus.BAD_REQUEST, ErrorType.INVALID_REQUEST, false)
    // LocalRefusal.refuseIfNodeWordUnheld: 503 api_error, x-should-retry false.
    Decline.NoSharedLogin -> WireAnswer(HttpStatus.SERVICE_UNAVAILABLE, ErrorType.API_ERROR, false)
    is Decline.BadBody, is Decline.UnknownModel, Decline.LivenessProbe, is Decline.Untranslatable ->
        WireAnswer(HttpStatus.BAD_REQUEST, ErrorType.INVALID_REQUEST, null)
}

/** Every generated case, with the answer ClientWire gives it. */
private data class Case(val cause: FailureCause, val at: Position) {
    val answer: WireAnswer by lazy { ClientWire.answer(cause, at) }
    val own: ErrorType get() = todayType(cause)

    override fun toString(): String = "$cause at $at"
}

private val CASES: List<Case> = CAUSES.flatMap { cause -> POSITIONS.map { Case(cause, it) } }

private fun cases(moment: Moment): List<Case> = CASES.filter { it.at.moment == moment }

/** Before content: the stream is open, or its status is still ours and the failure is no overflow. */
private fun beforeContent(): List<Case> =
    cases(Moment.COMMITTED) + cases(Moment.BEFORE_COMMIT).filter { it.cause !is FailureCause.Provider.TooLong }

class ClientWireTodayPropertyTest {
    @Test
    fun `a retryable failure before content is wired as overloaded_error`() {
        val retryable = beforeContent().filter {
            it.own == ErrorType.RATE_LIMIT || (it.own == ErrorType.API_ERROR && !todayPermanent(it.cause))
        }
        assertTrue(retryable.size > POSITIONS.size, "the generator reached the retryable leaves")
        retryable.forEach { case ->
            assertEquals(WireAnswer(null, ErrorType.OVERLOADED, null), case.answer, "$case")
        }
    }

    @Test
    fun `after content the type is the cause's own`() {
        cases(Moment.MID_CONTENT).forEach { case ->
            assertEquals(WireAnswer(null, case.own, null), case.answer, "$case")
            assertEquals(case.own, ClientWire.ownType(case.cause), "$case")
        }
    }

    @Test
    fun `invalid_request_error and authentication_error are never relabelled`() {
        val terminal = setOf(ErrorType.INVALID_REQUEST, ErrorType.AUTHENTICATION)
        val held = CASES.filter { it.own in terminal }
        assertTrue(held.isNotEmpty(), "the generator reached the request's own verdicts")
        held.forEach { case -> assertEquals(case.own, case.answer.type, "$case") }
    }

    @Test
    fun `a non-stream ending keeps its real status`() {
        cases(Moment.NON_STREAM).forEach { case ->
            assertEquals(WireAnswer(todayStatus(case.own), case.own, null), case.answer, "$case")
        }
    }

    @Test
    fun `a cause today's table relabels before content, NeedsPayment included, is relabelled as today`() {
        beforeContent().forEach { case ->
            val expected = todayBeforeContent(case.own, todayPermanent(case.cause))
            assertEquals(WireAnswer(null, expected, null), case.answer, "$case")
        }
        val payment = beforeContent().filter { it.cause is FailureCause.Provider.NeedsPayment }
        assertTrue(payment.isNotEmpty(), "the generator reached NeedsPayment")
        payment.forEach { case -> assertEquals(ErrorType.OVERLOADED, case.answer.type, "$case") }
    }

    @Test
    fun `a refusal before the stream opens answers today's status`() {
        cases(Moment.ADMISSION).forEach { case ->
            todayAdmission(case.cause)?.let { expected -> assertEquals(expected, case.answer, "$case") }
        }
        cases(Moment.BEFORE_COMMIT).filter { it.cause is FailureCause.Provider.TooLong }.forEach { case ->
            assertEquals(WireAnswer(HttpStatus.BAD_REQUEST, ErrorType.INVALID_REQUEST, false), case.answer, "$case")
        }
        assertNull(todayAdmission(FailureCause.Path.Stalled(Stall.IDLE)), "a stall is never an admission refusal")
    }
}
