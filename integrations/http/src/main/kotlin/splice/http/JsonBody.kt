// PORT-OF: daemon/control/.../api/JsonBody.kt (itself ControlServer.kt @ a77531a) — invariants unchanged: the request-body JSON parse that
// patchConfig and receiveLaunchRequest each wrote out identically — a cancellation-safe
// parseToJsonElement().jsonObject read that answers null on any failure (malformed body,
// non-object body, or a cancelled request) rather than throwing into the route handler.
package splice.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables

/** The shared `Json { ignoreUnknownKeys = true }` reader for the two routes that accept a JSON body. */
public class JsonBody {
    private val json = Json { ignoreUnknownKeys = true }

    // null is this function's contract and the failure is answered by the CALLER: every route turns
    // a null body into its own 4xx (see LaunchRoutes.receiveLaunchRequest's safe-by-default comment).
    // A logger here would duplicate, once per request, what the caller already tells the operator.
    public suspend fun parse(call: ApplicationCall): JsonObject? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- null is the contract and the caller answers the failure
        Cancellables.runCatchingCancellable { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
}
