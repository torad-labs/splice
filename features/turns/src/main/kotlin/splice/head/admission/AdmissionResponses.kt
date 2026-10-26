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
import splice.core.budget.BudgetBlock
import splice.core.failure.ClientWire
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.SpliceLine
import splice.core.failure.StoppedBy
import splice.core.failure.WireAnswer
import splice.core.wire.ErrorEnvelope
import splice.core.wire.HttpStatus
import splice.http.ingress.IngressErrorBody
import splice.upstream.credentials.AccountResetText
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared pre-turn HTTP bodies: what was declined, said in [voice]'s line (failure-handling slice 2), in the envelope
 *  every refusal before a turn takes. The real response status owns retry semantics, not the SSE rule. */
public class AdmissionErrorBody(private val voice: HeadVoice) : IngressErrorBody {
    override fun render(type: String, why: Decline): String = envelope(type, FailureCause.Splice.Declined(why), null)

    /** [cause] told as the client is told it ([answer], or ClientWire's answer at admission when null). */
    internal fun envelope(type: String, cause: FailureCause, answer: WireAnswer?): String {
        val told = answer ?: ClientWire.answer(cause, AT_ADMISSION)
        return ErrorEnvelope.of(type, voice.anyTurn.ending(cause, AT_ADMISSION, told).text).toString()
    }
}

/** Where every refusal before a turn is told: at admission, before any stream. */
private val AT_ADMISSION = Position(Moment.ADMISSION, streamBegan = false)

/** The admission plane's response shapes: one owner for every wire terminal a request can meet
 *  before a turn exists (400, 401, 403, 408, 413, 429, 503, 529). The words of each are the head's [voice]
 *  (failure-handling slice 2): a site names what it refused, never what the client reads. */
internal class AdmissionResponses(private val voice: HeadVoice) {
    private val retryAfterFormat = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC)

    // V4-102: the shape lives in core (splice.core.wire.ErrorEnvelope) so :upstream can build the same envelope, and
    // AdmissionErrorBody is the one place a refusal before a turn is put in it, here and at the heap ingress.
    private val bodies = AdmissionErrorBody(voice)

    /** The one shape every refusal before a turn takes: [cause] decides the status, the error type and
     *  x-should-retry (ClientWire at ADMISSION), and the head's voice says the line the client reads. Every admission
     *  path answers IDENTICALLY per cause because client retry logic keys on the shape (three hand-built
     *  copies of the 529 drifted; review 2026-07-22 round 3). */
    suspend fun respondRefusal(call: ApplicationCall, cause: FailureCause) {
        val answer = ClientWire.answer(cause, AT_ADMISSION)
        answer.shouldRetry?.let { call.response.header(SHOULD_RETRY_HEADER, it.toString()) }
        call.respondText(
            bodies.envelope(answer.type.wireName, cause, answer),
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

    /** The line [cause] is told with at admission: what a trace records beside the refusal that answers it. */
    fun lineOf(cause: FailureCause): SpliceLine = voice.anyTurn.ending(cause, AT_ADMISSION)

    /** The gate and its queue are full: [limit] turns already run on this head. */
    suspend fun respondAtCapacity(call: ApplicationCall, limit: Int) {
        respondRefusal(call, FailureCause.Splice.AtCapacity(limit))
    }

    /** The refusal of a request that reaches a head that is stopping. The connection closes after it, so the client's
     *  retry opens a new one, and a new one waits for the next daemon instead of coming back to this one. */
    suspend fun respondStopping(call: ApplicationCall) {
        call.response.header(HttpHeaders.Connection, "close")
        respondRefusal(call, FailureCause.Splice.Stopped(StoppedBy.HEAD_STOPPING))
    }

    /** Every login of the head is at its limit: [resetEpochSeconds] is the earliest reset, when one is known, which the
     *  line names, and [retryEpochSeconds] is when the client is told to come back (its Retry-After). */
    suspend fun respondRateLimited(call: ApplicationCall, resetEpochSeconds: Long?, retryEpochSeconds: Long?) {
        retryEpochSeconds?.let { call.response.header(HttpHeaders.RetryAfter, retryAfterDate(it)) }
        val lift = resetEpochSeconds?.let {
            Lift.At(AccountResetText.normalizedInstant(it), firm = true, source = LiftSource.RESET_HEADER)
        } ?: Lift.Unstated
        respondRefusal(call, FailureCause.Provider.Limited(lift, window = null))
    }

    private fun retryAfterDate(resetEpochSeconds: Long): String =
        retryAfterFormat.format(AccountResetText.normalizedInstant(resetEpochSeconds))

    /** A body over the head's [limit], [bytes] long where the request declared its length. */
    suspend fun respondTooLarge(call: ApplicationCall, limit: Int, bytes: Long?) {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.TooLarge(bytes = bytes, limit = limit.toLong())))
    }

    /** Only temporary pressure is retryable; an unfit body is refused against the effective reservation ceiling. */
    suspend fun respondHeapRefusal(call: ApplicationCall, weight: Long, limit: Long) {
        val why = if (weight > limit) Decline.TooLarge(bytes = weight, limit = limit) else Decline.HeapBusy
        respondRefusal(call, FailureCause.Splice.Declined(why))
    }

    /** The body did not arrive within the read bound, or its stream broke off. */
    suspend fun respondReadTimeout(call: ApplicationCall) {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.ReadTimeout))
    }

    /** v0.4.0: the DNS-rebinding refusal (see ClientAuth.admitsHost) — before any route runs. */
    suspend fun respondForeignHost(call: ApplicationCall) {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.ForeignHost))
    }

    /** V4-133 review: the head's `block` budget is reached (see HeadAdmission.refuseIfOverBudget). */
    suspend fun respondBudgetBlocked(call: ApplicationCall, block: BudgetBlock) {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.Budget(block.limit, block.resetsAt)))
    }

    /** The mgmt-key front door's refusal (see [ClientAuth.authorize]). */
    suspend fun respondUnauthorized(call: ApplicationCall) {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.BadLocalKey))
    }
}

/** The header Claude Code reads before re-sending a refused request. */
private const val SHOULD_RETRY_HEADER = "x-should-retry"
