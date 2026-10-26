// PORT-OF: splice/gateway/head/HeadServer.kt (AdmissionResponses, GATEWAY_CAPACITY_STATUS,
// CONTENT_TOO_LARGE_STATUS, INVALID_REQUEST_ERROR, and the four respondText bodies the admission
// paths built inline) @ 1caedd6 — invariants unchanged: every admission path answers IDENTICALLY
// per status, because client retry logic keys on the shape. Split out (HD-24) and WIDENED
// private -> internal: its own doc comment said it was file-private only because HeadServer sat at
// its 14-function budget, and four collaborators now respond through it.
package splice.head.admission

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import splice.core.auth.LoopbackHost
import splice.core.budget.BudgetBlock
import splice.core.failure.ClientWire
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.StoppedBy
import splice.core.wire.ErrorEnvelope
import splice.core.wire.HttpStatus
import splice.http.ingress.IngressErrorBody
import splice.upstream.credentials.AccountResetText
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared pre-turn HTTP bodies. The real response status owns retry semantics, not the SSE rule. */
public object AdmissionErrorBody : IngressErrorBody {
    override fun render(type: String, message: String): String = ErrorEnvelope.of(type, message).toString()
}

/** The admission plane's response shapes: one owner for every wire terminal a request can meet
 *  before a turn exists (400, 401, 403, 408, 413, 429, 503, 529). No instance state; pure response
 *  shaping. */
internal class AdmissionResponses {
    private val retryAfterFormat = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC)

    // Relocated from a HeadServer member so respondAtCapacity shares one body builder; pure JSON
    // shaping with no instance state (review 2026-07-22 round 3).
    // V4-102: the shape lives in core (splice.core.wire.ErrorEnvelope) so :upstream can build the
    // same envelope — it cannot import :daemon-head. Kept as a named delegate so the six admission
    // callers read unchanged; the local builder is gone, which is what the wall actually asks for.
    private fun errorBodyJson(type: String, message: String): String =
        AdmissionErrorBody.render(type, message)

    /** The one shape every refusal before a turn takes: [cause] decides the status, the error type and
     *  x-should-retry (ClientWire at ADMISSION), and [message] is what the client reads. Every admission
     *  path answers IDENTICALLY per cause because client retry logic keys on the shape (three hand-built
     *  copies of the 529 drifted; review 2026-07-22 round 3). */
    suspend fun respondRefusal(call: ApplicationCall, cause: FailureCause, message: String) {
        val answer = ClientWire.answer(cause, Position(Moment.ADMISSION, streamBegan = false))
        answer.shouldRetry?.let { call.response.header(SHOULD_RETRY_HEADER, it.toString()) }
        call.respondText(
            errorBodyJson(answer.type.wireName, message),
            ContentType.Application.Json,
            statusLine(checkNotNull(answer.status) { "an admission answer is an HTTP status" }),
        )
    }

    /** The reason phrases admission has always sent where Ktor's own differ or are missing. */
    private fun statusLine(status: Int): HttpStatusCode = when (status) {
        HttpStatus.OVERLOADED -> HttpStatusCode(status, "Gateway At Capacity")
        HttpStatus.TOO_MANY_REQUESTS -> HttpStatusCode(status, "Rate Limited")
        HttpStatus.CONTENT_TOO_LARGE -> HttpStatusCode(status, "Content Too Large")
        else -> HttpStatusCode.fromValue(status)
    }

    /** The gate and its queue are full: [limit] turns already run on this head. */
    suspend fun respondAtCapacity(call: ApplicationCall, limit: Int, message: String) {
        respondRefusal(call, FailureCause.Splice.AtCapacity(limit), message)
    }

    /** The refusal of a request that reaches a head that is stopping. The connection closes after it, so the client's
     *  retry opens a new one, and a new one waits for the next daemon instead of coming back to this one. */
    suspend fun respondStopping(call: ApplicationCall) {
        call.response.header(HttpHeaders.Connection, "close")
        respondRefusal(call, FailureCause.Splice.Stopped(StoppedBy.HEAD_STOPPING), "head is stopping; retry")
    }

    /** Every login of the head is at its limit; [resetEpochSeconds] is the earliest reset, when one is known. */
    suspend fun respondRateLimited(call: ApplicationCall, message: String, resetEpochSeconds: Long?) {
        resetEpochSeconds?.let { call.response.header(HttpHeaders.RetryAfter, retryAfterDate(it)) }
        val lift = resetEpochSeconds?.let {
            Lift.At(AccountResetText.normalizedInstant(it), firm = true, source = LiftSource.RESET_HEADER)
        } ?: Lift.Unstated
        respondRefusal(call, FailureCause.Provider.Limited(lift, window = null), message)
    }

    private fun retryAfterDate(resetEpochSeconds: Long): String =
        retryAfterFormat.format(AccountResetText.normalizedInstant(resetEpochSeconds))

    suspend fun respondTooLarge(call: ApplicationCall, limit: Int) {
        respondRefusal(
            call,
            FailureCause.Splice.Declined(Decline.TooLarge(bytes = null, limit = limit.toLong())),
            "request body exceeds $limit bytes",
        )
    }

    /** Only temporary pressure is retryable; an unfit body names the effective reservation ceiling. */
    suspend fun respondHeapRefusal(call: ApplicationCall, weight: Long, limit: Long) {
        if (weight > limit) {
            respondRefusal(
                call,
                FailureCause.Splice.Declined(Decline.TooLarge(bytes = weight, limit = limit)),
                "request body requires $weight bytes; materialization heap limit is $limit bytes",
            )
        } else {
            respondRefusal(call, FailureCause.Splice.Declined(Decline.HeapBusy), "gateway busy; retry")
        }
    }

    suspend fun respondReadTimeout(call: ApplicationCall, message: String = "request body read timed out") {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.ReadTimeout), message)
    }

    /** v0.4.0: the DNS-rebinding refusal (see ClientAuth.admitsHost) — before any route runs. */
    suspend fun respondForeignHost(call: ApplicationCall) {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.ForeignHost), LoopbackHost.FOREIGN_HOST_REFUSAL)
    }

    /** V4-133 review: the head's `block` budget is reached (see HeadAdmission.refuseIfOverBudget). */
    suspend fun respondBudgetBlocked(call: ApplicationCall, block: BudgetBlock) {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.Budget(block.limit, block.resetsAt)), block.message)
    }

    /** The mgmt-key front door's refusal (see [ClientAuth.authorize]). */
    suspend fun respondUnauthorized(call: ApplicationCall, message: String = "invalid local gateway credentials") {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.BadLocalKey), message)
    }
}

/** The header Claude Code reads before re-sending a refused request. */
private const val SHOULD_RETRY_HEADER = "x-should-retry"
