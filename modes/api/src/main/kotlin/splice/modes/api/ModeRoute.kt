// NEW: a management route a mode answers (modes/README.md, "The contract"). splice serves it on the control port
// under its exact path, behind the management key, and turns this framework-free request and response into its own.
package splice.modes.api

import kotlinx.serialization.json.JsonElement

/** A route: its [method] (`GET`, `PUT`, ...), its exact [path] (under `/api/`), and what answers it. */
public data class ModeRoute(
    val method: String,
    val path: String,
    val handler: ModeRouteHandler,
)

/** What answers a route's request. A handler that throws is answered with a 500 that names the exception's class. */
public fun interface ModeRouteHandler {
    public fun handle(request: ModeRequest): ModeResponse
}

/** One request: its method and path, its query parameters (the first value of each), its JSON body or null when it
 *  has none, and the mode's store. */
public data class ModeRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val body: JsonElement?,
    val store: ModeStore,
)

/** The answer: an HTTP status and a JSON body. */
public data class ModeResponse(val status: Int, val body: JsonElement)
