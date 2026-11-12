// NEW: failure-handling slice 5 (2026-10-27). The console's try-now on a held login (final-design.md section 9, "What
// wakes it"): the turn held longest on the login probes at once, and every turn held there continues when it serves.
// POST /api/heads/{head}/holds/try-now, with ?login=<key> for one login; without it, every held login of the head.
package splice.app.control

import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import splice.app.control.mount.ControlGuard
import splice.head.turn.LiveTurnsRoutes

/** Registers the try-now beside the head's live turns, which it reads through [routes]. */
internal class HoldTryNowRoute(private val routes: LiveTurnsRoutes, private val guard: ControlGuard) {
    fun register(route: Route) {
        route.post("/api/heads/{head}/holds/try-now") {
            guard.guarded(call) {
                routes.tryNow(call.parameters["head"].orEmpty(), call.request.queryParameters["login"]).send(call)
            }
        }
    }
}
