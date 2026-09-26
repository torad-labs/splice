// NEW: LAYOUT-01 — the configuration capability's routes: the layered knobs and the topology file
// (features/configuration).
package splice.app.control.mount

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.put
import splice.app.control.ConsolePorts
import splice.configuration.knobs.ConfigRoutes
import splice.configuration.topology.TopologyRoutes
import splice.configuration.topology.TopologyStale
import splice.core.config.ConfigService
import splice.core.topology.TopologyWriterSource
import splice.http.JsonBody

internal class ConfigurationMount(
    config: ConfigService,
    topologyStale: TopologyStale,
    ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val configRoutes = ConfigRoutes(config, JsonBody())

    // Read at CALL time: ControlPlane assigns [ConsolePorts.topology] after the server is constructed.
    private val topologyRoutes = TopologyRoutes(TopologyWriterSource { ports.topology }, topologyStale)

    fun register(route: Route) {
        route.get("/api/config") {
            // JW-06: ?head=<key> folds that head's override layer into `effective`.
            guard.guarded(call) {
                ControlReplies.respond(call, configRoutes.configJson(call.request.queryParameters["head"]))
            }
        }
        route.patch("/api/config") { guard.guarded(call) { configRoutes.patchConfig(call) } }
        route.get("/api/topology") { guard.guarded(call) { topologyRoutes.read().send(call) } }
        route.put("/api/topology") { guard.guarded(call) { topologyRoutes.write(call.receiveText()).send(call) } }
    }
}
