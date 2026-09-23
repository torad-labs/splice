// PORT-OF: control/api/sessions/SessionsRoutes.kt — shared Ktor JSON response, unchanged on the wire.
package splice.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

/** A JSON body and status shared by capability endpoints, independent of session ownership. */
public data class JsonReply(public val status: HttpStatusCode, public val body: String) {
    public suspend fun send(call: ApplicationCall) {
        call.respondText(body, ContentType.Application.Json, status)
    }
}
