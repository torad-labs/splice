// NEW: LAYOUT-01 — the events capability's route: the console's live stream at /api/events
// (features/events).
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.ConsolePorts
import splice.events.stream.EventsRoute

/** V4-134: answered on /api/events until ControlPlane assigns [ConsolePorts.events]. NOT an empty
 *  stream: a stream that opens and stays quiet reads as a daemon with nothing happening, which is a
 *  confident false negative about a daemon serving turns right now. The text names what was not done. */
private const val EVENTS_UNWIRED = "the daemon wired no console event bus; /api/events cannot stream its events"

internal class EventsMount(private val ports: ConsolePorts, private val guard: ControlGuard) {
    fun register(route: Route) {
        // V4-126: additive. Every poll route is untouched and stays the fallback.
        route.get("/api/events") { guard.guarded(call) { streamEvents(call) } }
    }

    /** Reads [ConsolePorts.events] at CALL time: ControlPlane assigns it after construction, so a
     *  route that captured the value would capture null forever. */
    private suspend fun streamEvents(call: ApplicationCall) {
        val bus = ports.events
        if (bus == null) {
            call.respondText(
                buildJsonObject { put("error", EVENTS_UNWIRED) }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        EventsRoute(bus).stream(call)
    }
}
