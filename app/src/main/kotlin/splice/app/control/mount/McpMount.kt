// NEW: LAYOUT-01 — the shared MCP host's routes: its status and the per-server HTTP transport
// (integrations/mcp). Mounted only when the daemon hosts MCP at all. MCP hub step 1 (docs/specs/mcp-hub.md): /api/mcp
// also carries `plans`, the servers Claude Code would load for each folder a running session started in.
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.app.control.McpLaunchFolders
import splice.control.mcp.McpHost
import splice.control.mcp.McpRoutes
import splice.control.mcp.hub.McpHubRoutes

/** The /mcp/{name} rows are the only ones that also admit the MCP access key ([Door.MCP]). The host's sweeper
 *  and its open sessions belong to this mount: the server starts and stops them through [start] and [stop]. */
internal class McpMount(
    private val host: McpHost,
    private val guard: ControlGuard,
    private val plans: McpLaunchFolders,
) : ControlMount, BoundResource {
    private val routes = McpRoutes(host)
    private val hub = host.hub?.let(::McpHubRoutes)

    override fun register(route: Route) {
        route.get("/api/mcp") { guard.guarded(call) { ControlReplies.respond(call, status()) } }
        route.post("/api/mcp/{name}/test") {
            guard.guarded(call) {
                ControlReplies.respond(call, host.probe.test(call.parameters["name"].orEmpty()).json())
            }
        }
        // MCP hub steps 3 and 4: the front's calls, management key only.
        hub?.let { front ->
            route.post("/api/mcp/hub/resolve") { guard.guarded(call) { front.resolve(call) } }
            route.post("/api/mcp/hub/start") { guard.guarded(call) { front.start(call) } }
            route.post("/api/mcp/hub/listing") { guard.guarded(call) { front.listing(call) } }
            route.post("/api/mcp/hub/roots") { guard.guarded(call) { front.roots(call) } }
        }
        route.post("/mcp/{name}") { guard.guarded(call, Door.MCP) { routes.post(call) } }
        route.get("/mcp/{name}") { guard.guarded(call, Door.MCP) { routes.stream(call) } }
        route.delete("/mcp/{name}") { guard.guarded(call, Door.MCP) { routes.delete(call) } }
    }

    /** The host's status with the plans beside it. */
    private fun status(): String =
        JsonObject(Json.parseToJsonElement(host.statusJson()).jsonObject + ("plans" to plans.json())).toString()

    override fun start() = host.start()

    override fun stop() = host.stop()
}
