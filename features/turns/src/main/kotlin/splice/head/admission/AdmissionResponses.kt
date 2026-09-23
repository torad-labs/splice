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
import splice.core.wire.ErrorEnvelope
import splice.core.wire.HttpStatus
import splice.upstream.credentials.AccountResetText
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val INVALID_REQUEST_ERROR = "invalid_request_error"

/** The admission plane's response shapes: one owner for all five wire terminals a request can meet
 *  before a turn exists (400, 401, 408, 413, 529). No instance state; pure response shaping. */
internal class AdmissionResponses {
    private val retryAfterFormat = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC)

    // Relocated from a HeadServer member so respondAtCapacity shares one body builder; pure JSON
    // shaping with no instance state (review 2026-07-22 round 3).
    // V4-102: the shape lives in core (splice.core.wire.ErrorEnvelope) so :upstream can build the
    // same envelope — it cannot import :daemon-head. Kept as a named delegate so the six admission
    // callers read unchanged; the local builder is gone, which is what the wall actually asks for.
    private fun errorBodyJson(type: String, message: String): String =
        ErrorEnvelope.of(type, message).toString()

    /** The 529 capacity terminal built once: every admission path must answer IDENTICALLY because
     *  client retry logic keys on the shape (three hand-built copies drifted; review 2026-07-22
     *  round 3). */
    suspend fun respondAtCapacity(call: ApplicationCall, message: String) {
        call.respondText(
            errorBodyJson("overloaded_error", message),
            ContentType.Application.Json,
            HttpStatusCode(HttpStatus.OVERLOADED, "Gateway At Capacity"),
        )
    }

    /** The client-400: an unparseable body, or a model this head does not proxy. */
    suspend fun respondInvalidRequest(call: ApplicationCall, message: String) {
        call.respondText(
            errorBodyJson(INVALID_REQUEST_ERROR, message),
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
    }

    suspend fun respondRateLimited(call: ApplicationCall, message: String, resetEpochSeconds: Long?) {
        resetEpochSeconds?.let { call.response.header(HttpHeaders.RetryAfter, retryAfterDate(it)) }
        call.respondText(
            errorBodyJson("rate_limit_error", message),
            ContentType.Application.Json,
            HttpStatusCode(HttpStatus.TOO_MANY_REQUESTS, "Rate Limited"),
        )
    }

    private fun retryAfterDate(resetEpochSeconds: Long): String =
        retryAfterFormat.format(AccountResetText.normalizedInstant(resetEpochSeconds))

    suspend fun respondTooLarge(call: ApplicationCall, limit: Int) {
        call.respondText(
            errorBodyJson(INVALID_REQUEST_ERROR, "request body exceeds $limit bytes"),
            ContentType.Application.Json,
            HttpStatusCode(HttpStatus.CONTENT_TOO_LARGE, "Content Too Large"),
        )
    }

    suspend fun respondReadTimeout(call: ApplicationCall, message: String = "request body read timed out") {
        call.respondText(
            errorBodyJson(INVALID_REQUEST_ERROR, message),
            ContentType.Application.Json,
            HttpStatusCode.RequestTimeout,
        )
    }

    /** The mgmt-key front door's refusal (see [ClientAuth.authorize]). */
    suspend fun respondUnauthorized(call: ApplicationCall, message: String = "invalid local gateway credentials") {
        call.respondText(
            errorBodyJson("authentication_error", message),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
    }
}
