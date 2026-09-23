// NEW: LAYOUT-01 — the diagnostics capability's routes: the doctor report and the one never-recorded
// playground call (features/diagnostics).
package splice.app.control.mount

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.ConsolePorts
import splice.app.control.PlaygroundHeadAdapter
import splice.app.control.api.HeadResolver
import splice.diagnostics.doctor.DoctorRoute
import splice.diagnostics.playground.PlaygroundRoute
import splice.diagnostics.playground.PlaygroundSource

/** [ports] is read at CALL time: ControlPlane assigns [ConsolePorts.doctor] and
 *  [ConsolePorts.playground] after the server is constructed. */
internal class DiagnosticsMount(
    resolver: HeadResolver,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val doctorRoute = DoctorRoute()
    private val playgroundRoute =
        PlaygroundRoute(PlaygroundHeadAdapter.lookup(resolver), PlaygroundSource { ports.playground })

    fun register(route: Route) {
        route.get("/api/doctor") { guard.guarded(call) { doctorRoute.doctorJson(call, ports.doctor) } }
        route.post("/api/playground") { guard.guarded(call) { playgroundRoute.run(call.receiveText()).send(call) } }
    }
}
