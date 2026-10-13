// NEW: LAYOUT-01 — the models capability's route: /api/models over each head's roster (features/models).
// §9i: and /api/models/local, what each local runtime says about its models.
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.RosterHeadAdapter
import splice.models.list.LocalRuntimesRoute
import splice.models.list.LocalRuntimesSource
import splice.models.roster.ModelsRoute
import splice.upstream.codemode.ProcessDispatchers

/** [ports] is read at CALL time: ControlPlane assigns [ConsolePorts.declaredHeads] after construction. */
internal class ModelsMount(
    heads: Map<String, ManagedHead>,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val modelsRoute = ModelsRoute(RosterHeadAdapter.heads(heads))
    private val localRoute = LocalRuntimesRoute(LocalRuntimesSource { ports.localRuntimes }, ProcessDispatchers().io())

    fun register(route: Route) {
        route.get("/api/models") { guard.guarded(call) { modelsRoute.models(call, ports.declaredHeads) } }
        route.get("/api/models/local") { guard.guarded(call) { localRoute.local(call) } }
    }
}
