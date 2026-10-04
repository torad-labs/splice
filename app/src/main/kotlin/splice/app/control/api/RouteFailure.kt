// NEW: 2026-09-23, what a management route that threw answers. Ktor's own answer is an empty 500
// logged through SLF4J, and the daemon ships no SLF4J provider, so a route failure left no trace
// anywhere: the gate of record on 00829fb50 saw `500 GET /api/doctor` twice in the console e2e, and
// nothing in any log said why. The failure now reaches the daemon log with the route and the kind of
// failure, and the client gets the same line as a JSON error. The kind is printed where the message
// is not: SafeFailureText withholds a message that may quote file bytes.
package splice.app.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.SafeFailureText
import java.io.IOException

internal class RouteFailure(private val audit: ControlAudit) {

    suspend fun answer(call: ApplicationCall, failure: Throwable) {
        val committed = call.response.isCommitted
        // The client leaving a body already on the wire is how every /api/events stream ends (a tab
        // closed), not a route failure: measured, the console e2e logged one per page before this.
        if (committed && failure is IOException) return
        val why = "${kind(failure)}: ${SafeFailureText.render(failure)}"
        audit.routeFailed(call.request.httpMethod.value, call.request.path(), why)
        // A body already on the wire (a stream that failed part-way) cannot take a status line.
        if (committed) return
        call.respondText(
            buildJsonObject { put("error", why) }.toString(),
            ContentType.Application.Json,
            HttpStatusCode.InternalServerError,
        )
    }

    /** The failure's kind from a closed `when` (no reflection in production), each subtype before the
     *  type it extends: a SerializationException is an IllegalArgumentException. */
    private fun kind(failure: Throwable): String = when (failure) {
        is IOException -> "IOException"
        is SerializationException -> "SerializationException"
        is IllegalArgumentException -> "IllegalArgumentException"
        is IllegalStateException -> "IllegalStateException"
        is NullPointerException -> "NullPointerException"
        is IndexOutOfBoundsException -> "IndexOutOfBoundsException"
        else -> "unclassified failure"
    }
}
