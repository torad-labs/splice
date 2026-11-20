// NEW: a mode's management route served on the control port (modes/README.md, "The contract"): Ktor's request turned
// into the framework-free ModeRequest, the mode's answer turned back into Ktor's response.
package splice.app.modes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.HttpStatus
import splice.http.JsonReply
import splice.modes.api.ModeRequest
import splice.modes.api.ModeResponse
import splice.modes.api.ModeRoute
import splice.modes.api.ModeStore

/** Serves [route] of mode [mode], whose store is [store]. */
internal class ModeRouteCall(private val mode: String, private val route: ModeRoute, private val store: ModeStore) {
    suspend fun answer(call: ApplicationCall) {
        val body = call.receiveText().takeIf(String::isNotBlank)
        val parsed = body?.let(::json)
        if (body != null && parsed == null) {
            JsonReply(HttpStatusCode.BadRequest, error("the body is not JSON")).send(call)
            return
        }
        val query = call.request.queryParameters.entries().associate { (key, values) -> key to values.first() }
        val request = ModeRequest(call.request.httpMethod.value, call.request.path(), query, parsed, store)
        val response = when (val run = ModeFailure.run(mode) { route.handler.handle(request) }) {
            is ModeRun.Done -> run.value
            is ModeRun.Threw -> ModeResponse(HttpStatus.INTERNAL_SERVER_ERROR, Json.parseToJsonElement(error(run.said)))
        }
        JsonReply(HttpStatusCode.fromValue(response.status), response.body.toString()).send(call)
    }

    private fun json(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    private fun error(text: String): String = buildJsonObject { put("error", text) }.toString()
}
