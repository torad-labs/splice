// PORT-OF: ControlServer.kt @ a77531a — invariants unchanged: the request-body JSON parse that
// patchConfig and receiveLaunchRequest each wrote out identically — a cancellation-safe
// parseToJsonElement().jsonObject read that answers null on any failure (malformed body,
// non-object body, or a cancelled request) rather than throwing into the route handler.
package splice.control.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables

/** The shared `Json { ignoreUnknownKeys = true }` reader for the two routes that accept a JSON body. */
internal class JsonBody {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun parse(call: ApplicationCall): JsonObject? =
        Cancellables.runCatchingCancellable { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
}
