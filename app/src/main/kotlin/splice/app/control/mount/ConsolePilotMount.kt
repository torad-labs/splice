// NEW: Oct 16, 2026 — the console pilot's five rows (ConsolePilot): the CLI's order, and the open page's ask, answer,
// focus and leave. All five take the management key, which both the CLI and the console already carry.
package splice.app.control.mount

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.ConsolePilot

internal class ConsolePilotMount(private val guard: ControlGuard) {
    private val pilot = ConsolePilot()

    fun register(route: Route) {
        route.post("/api/console/pilot") { guard.guarded(call) { pilot.order(call.receiveText()).send(call) } }
        route.get("/api/console/pilot/next") {
            guard.guarded(call) {
                val asks = call.request.queryParameters
                pilot.next(asks["tab"], asks["focused"] == "1").send(call)
            }
        }
        route.post("/api/console/pilot/answer") { guard.guarded(call) { pilot.answer(call.receiveText()).send(call) } }
        route.post("/api/console/pilot/focus") {
            guard.guarded(call) { pilot.focus(call.request.queryParameters["tab"]).send(call) }
        }
        route.post("/api/console/pilot/leave") {
            guard.guarded(call) { pilot.leave(call.request.queryParameters["tab"]).send(call) }
        }
    }
}
