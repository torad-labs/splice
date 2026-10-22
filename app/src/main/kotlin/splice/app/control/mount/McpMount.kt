// NEW: LAYOUT-01 — the shared MCP host's routes: its status and the per-server HTTP transport
// (integrations/mcp). Mounted only when the daemon hosts MCP at all. MCP hub step 1 (docs/specs/mcp-hub.md): /api/mcp
// also carries `plans`, the servers Claude Code would load for each folder a running session started in.
package splice.app.control.mount

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.app.control.McpLaunchFolders
import splice.app.control.api.mcp.McpAccessRoutes
import splice.control.mcp.McpHost
import splice.control.mcp.McpRoutes
import splice.control.mcp.hub.McpHubRoutes

/** The /mcp/{name} rows are the only ones that also admit the MCP access key ([Door.MCP]). The host's sweeper
 *  and its open sessions belong to this mount: the server starts and stops them through [start] and [stop]. */
internal class McpMount(
    private val host: McpHost,
    private val guard: ControlGuard,
    private val plans: McpLaunchFolders,
    private val services: splice.app.control.McpServiceRefresh? = null,
) : ControlMount, BoundResource {
    private val routes = McpRoutes(host)
    private val hub = host.hub?.let(::McpHubRoutes)
    private val access = McpAccessRoutes(guard.mcpAccessKey)

    override fun register(route: Route) {
        route.get("/api/mcp") { guard.guarded(call) { ControlReplies.respond(call, status()) } }
        registerReplacements(route)
        route.post("/api/mcp/access-key/rotate") { guard.guarded(call) { access.rotate(call) } }
        route.post("/api/mcp/access-key/headers") { guard.guarded(call) { access.headers(call) } }
        route.post("/api/mcp/services/refresh") {
            guard.guarded(call) {
                val reply = services?.refresh()
                    ?: splice.control.mcp.McpReply(splice.core.wire.HttpStatus.OK, "{\"ok\":true,\"registered\":false}")
                call.respondText(
                    reply.body.orEmpty(),
                    io.ktor.http.ContentType.Application.Json,
                    io.ktor.http.HttpStatusCode.fromValue(reply.status),
                )
            }
        }
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
            route.post("/api/mcp/hub/replacement/start") { guard.guarded(call) { front.replacementStart(call) } }
            route.post("/api/mcp/hub/replacement/retire") { guard.guarded(call) { front.replacementRetire(call) } }
            route.post("/api/mcp/hub/replacement/commit") { guard.guarded(call) { front.replacementCommit(call) } }
            route.post("/api/mcp/hub/replacement/rollback") { guard.guarded(call) { front.replacementRollback(call) } }
            route.post("/api/mcp/hub/replacement/exit") { guard.guarded(call) { front.replacementExit(call) } }
        }
        route.post("/mcp/{name}") { guard.guarded(call, Door.MCP) { routes.post(call) } }
        route.get("/mcp/{name}") { guard.guarded(call, Door.MCP) { routes.stream(call) } }
        route.delete("/mcp/{name}") { guard.guarded(call, Door.MCP) { routes.delete(call) } }
    }

    private fun registerReplacements(route: Route) {
        route.get("/api/mcp/{name}/replacement") {
            guard.guarded(call) {
                respondReplacement(call, host.replacements.status(call.parameters["name"].orEmpty()))
            }
        }
        route.post("/api/mcp/{name}/drain") {
            guard.guarded(call) {
                respondReplacement(call, host.replacements.drain(call.parameters["name"].orEmpty()))
            }
        }
        route.post("/api/mcp/{name}/resume") {
            guard.guarded(call) {
                respondReplacement(call, host.replacements.resume(call.parameters["name"].orEmpty()))
            }
        }
        route.post("/api/mcp/{name}/replace") {
            guard.guarded(call) {
                respondReplacement(call, host.replacements.replace(call.parameters["name"].orEmpty()))
            }
        }
    }

    private suspend fun respondReplacement(
        call: io.ktor.server.application.ApplicationCall,
        reply: splice.control.mcp.McpReply,
    ) {
        call.respondText(
            reply.body.orEmpty(),
            io.ktor.http.ContentType.Application.Json,
            io.ktor.http.HttpStatusCode.fromValue(reply.status),
        )
    }

    /** The host's status with the plans beside it, and each census row's place in them (`launches`), read once. */
    private fun status(): String {
        val status = Json.parseToJsonElement(host.statusJson()).jsonObject
        val read = plans.read()
        val sources = plans.placed(status["sources"] as? JsonObject, read)
        val placed = listOfNotNull(sources?.let { "sources" to it }, "plans" to plans.json(read))
        return JsonObject(status + placed).toString()
    }

    override fun start() = host.start()

    override fun stop() = host.stop()
}
