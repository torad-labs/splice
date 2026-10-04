// NEW: LAYOUT-01 — the models capability's route: /api/models over each head's roster (features/models).
// V4-239: and /api/models/upstream, `splice models` for the console, asked on demand only.
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.RosterHeadAdapter
import splice.core.util.EnvReader
import splice.models.list.ModelsReporterSource
import splice.models.list.UpstreamModelsRoute
import splice.models.roster.ModelsRoute
import splice.upstream.codemode.ProcessDispatchers

/** [ports] is read at CALL time: ControlPlane assigns [ConsolePorts.declaredHeads] after construction. */
internal class ModelsMount(
    heads: Map<String, ManagedHead>,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val modelsRoute = ModelsRoute(RosterHeadAdapter.heads(heads))
    private val upstreamRoute = UpstreamModelsRoute(
        ModelsReporterSource { ports.upstreamModels },
        EnvReader(System::getenv),
        ProcessDispatchers().io(),
    )

    fun register(route: Route) {
        route.get("/api/models") { guard.guarded(call) { modelsRoute.models(call, ports.declaredHeads) } }
        route.get("/api/models/upstream") {
            guard.guarded(call) { upstreamRoute.upstream(call, call.request.queryParameters["provider"]) }
        }
    }
}
