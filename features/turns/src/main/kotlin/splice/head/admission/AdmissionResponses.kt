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
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingPipelineCall
import splice.core.budget.BudgetBlock
import splice.core.failure.ClientWire
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.SpliceLine
import splice.core.failure.StoppedBy
import splice.core.failure.WireAnswer
import splice.core.wire.ErrorEnvelope
import splice.core.wire.HttpStatus
import splice.http.ingress.IngressErrorBody

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

    /** The refusal of a request that reaches a head that is stopping: its connection is closed with no HTTP answer.
     *  Claude Code 2.1.312 moves a session with a fallback model onto it after three overloaded answers in a row, and
     *  at once after any other 5xx, but never on a connection error, which it retries up to ten times; that retry opens
     *  a new connection and reaches the next daemon (Eli, Oct 28: restart drains moved Haiku and Sonnet sessions onto
     *  their Opus fallbacks). Nothing went upstream for the request, so nothing is lost or done twice. Every head runs
     *  on Netty; an engine with no connection to drop, such as Ktor's test engine, still answers the stopping 529. */
    suspend fun respondStopping(call: ApplicationCall) {
        val connection = nettyCall(call)?.context?.channel()
        if (connection != null) {
            connection.close()
            return
        }
        call.response.header(HttpHeaders.Connection, "close")
        respondRefusal(call, FailureCause.Splice.Stopped(StoppedBy.HEAD_STOPPING))
    }

    private fun nettyCall(call: ApplicationCall): NettyApplicationCall? {
        val pipeline = (call as? RoutingCall)?.pipelineCall ?: call
        val engine = (pipeline as? RoutingPipelineCall)?.engineCall ?: pipeline
        return engine as? NettyApplicationCall
    }

    /** A body over the head's [limit]. Both cap arms answer these same bytes (V4-440), and the arm that refuses a
     *  frame into the read never learns the body's size, so the line names the limit alone. */
    suspend fun respondTooLarge(call: ApplicationCall, limit: Int) {
        respondRefusal(call, FailureCause.Splice.Declined(Decline.TooLarge(bytes = null, limit = limit.toLong())))
    }

    /** Only temporary pressure is retryable; a body whose [weight] is over the effective reservation ceiling is one
     *  splice cannot give the memory to, whatever else is running. */
    suspend fun respondHeapRefusal(call: ApplicationCall, weight: Long, limit: Long) {
        val why = if (weight > limit) Decline.Unfit(weight, limit) else Decline.HeapBusy
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
