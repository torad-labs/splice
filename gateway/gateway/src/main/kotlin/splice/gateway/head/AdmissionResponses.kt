// PORT-OF: splice/gateway/head/HeadServer.kt (AdmissionResponses, GATEWAY_CAPACITY_STATUS,
// CONTENT_TOO_LARGE_STATUS, INVALID_REQUEST_ERROR, and the four respondText bodies the admission
// paths built inline) @ 1caedd6 — invariants unchanged: every admission path answers IDENTICALLY
// per status, because client retry logic keys on the shape. Split out (HD-24) and WIDENED
// private -> internal: its own doc comment said it was file-private only because HeadServer sat at
// its 14-function budget, and four collaborators now respond through it.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Same numeric convention as UpstreamFailureClassifier's OVERLOADED_STATUS (kept as its own const
// to avoid a cross-module const import and satisfy detekt MagicNumber).
private const val GATEWAY_CAPACITY_STATUS = 529
private const val CONTENT_TOO_LARGE_STATUS = 413
private const val INVALID_REQUEST_ERROR = "invalid_request_error"

/** The admission plane's response shapes: one owner for all five wire terminals a request can meet
 *  before a turn exists (400, 401, 408, 413, 529). No instance state; pure response shaping. */
internal class AdmissionResponses {
    // Relocated from a HeadServer member so respondAtCapacity shares one body builder; pure JSON
    // shaping with no instance state (review 2026-07-22 round 3).
    private fun errorBodyJson(type: String, message: String): String = buildJsonObject {
        put("type", "error")
        put(
            "error",
            buildJsonObject {
                put("type", type)
                put("message", message)
            },
        )
    }.toString()

    /** The 529 capacity terminal built once: every admission path must answer IDENTICALLY because
     *  client retry logic keys on the shape (three hand-built copies drifted; review 2026-07-22
     *  round 3). */
    suspend fun respondAtCapacity(call: ApplicationCall, message: String) {
        call.respondText(
            errorBodyJson("overloaded_error", message),
            ContentType.Application.Json,
            HttpStatusCode(GATEWAY_CAPACITY_STATUS, "Gateway At Capacity"),
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

    suspend fun respondTooLarge(call: ApplicationCall, limit: Int) {
        call.respondText(
            errorBodyJson(INVALID_REQUEST_ERROR, "request body exceeds $limit bytes"),
            ContentType.Application.Json,
            HttpStatusCode(CONTENT_TOO_LARGE_STATUS, "Content Too Large"),
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
    suspend fun respondUnauthorized(call: ApplicationCall) {
        call.respondText(
            errorBodyJson("authentication_error", "invalid local gateway credentials"),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
    }
}
