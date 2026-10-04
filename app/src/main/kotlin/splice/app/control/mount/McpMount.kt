// NEW: LAYOUT-01 — the shared MCP host's routes: its status and the per-server HTTP transport
// (integrations/mcp). Mounted only when the daemon hosts MCP at all.
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.control.mcp.McpHost
import splice.control.mcp.McpRoutes

/** The /mcp/{name} rows are the only ones that also admit the MCP access key ([Door.MCP]). */
internal class McpMount(private val host: McpHost, private val guard: ControlGuard) {
    private val routes = McpRoutes(host)

    fun register(route: Route) {
        route.get("/api/mcp") { guard.guarded(call) { ControlReplies.respond(call, host.statusJson()) } }
        route.post("/mcp/{name}") { guard.guarded(call, Door.MCP) { routes.post(call) } }
        route.get("/mcp/{name}") { guard.guarded(call, Door.MCP) { routes.stream(call) } }
        route.delete("/mcp/{name}") { guard.guarded(call, Door.MCP) { routes.delete(call) } }
    }
}
