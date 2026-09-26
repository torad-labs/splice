// NEW: LAYOUT-01 — the management-key check every control route runs behind, lifted out of ControlServer
// so each capability's mount guards its own rows with the one check rather than a copy of it. A route
// that throws past the check is answered by RouteFailure (2026-09-23), in the daemon log and in its 500.
// The v0.4.0 scoped doors and the foreign-Host refusal (d2a22c31, 94e9d705) landed here from
// ControlServer, where they were written before the split.
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.MgmtRoute
import splice.app.control.api.ControlAudit
import splice.app.control.api.RouteFailure
import splice.client.mcp.McpAccessKey
import splice.core.auth.ForeignHostLog
import splice.core.auth.LoopbackHost
import splice.core.config.MgmtKey
import splice.core.config.TurnKey
import splice.core.util.Cancellables
import splice.core.util.LogSink

/** Which scoped bearer a route admits BESIDE the management key, which opens every route. One door
 *  per route, so no route can be widened to two scoped keys by a flag pair. */
internal enum class Door { MANAGEMENT, MCP, SESSION }

/** Runs [MgmtRoute] only after the bearer matched the management key or the key of the route's [Door]:
 *  the MCP access key opens only the /mcp/{name} routes, and a launched session's turn key only the
 *  routes its own hooks call. */
internal class ControlGuard(private val mgmtKey: MgmtKey, audit: ControlAudit, log: LogSink) {
    private val mcpAccessKey = McpAccessKey(mgmtKey::get)

    // v0.4.0: the credential a launched session holds opens its OWN hooks' routes and nothing else.
    private val turnKey = TurnKey(mgmtKey)
    private val routeFailure = RouteFailure(audit)
    private val foreignHosts = ForeignHostLog("the control plane", log)

    /** v0.4.0: a request naming a non-loopback Host is a DNS-rebinding page in the operator's browser
     *  (see LoopbackHost). Refused before routing, so no route — guarded or open — runs for it, and
     *  said in the daemon log once per name (ForeignHostLog). */
    fun refuseForeignHosts(app: Application) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            val host = call.request.headers[HttpHeaders.Host]
            if (!LoopbackHost.admits(host)) {
                foreignHosts.refused(host.orEmpty())
                call.respondText(
                    buildJsonObject { put("error", LoopbackHost.FOREIGN_HOST_REFUSAL) }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.Forbidden,
                )
                finish()
            }
        }
    }

    suspend fun guarded(call: ApplicationCall, door: Door = Door.MANAGEMENT, block: MgmtRoute) {
        val header = call.request.headers["Authorization"]
        val authorized = mgmtKey.matchesBearer(header) || when (door) {
            Door.MANAGEMENT -> false
            Door.MCP -> mcpAccessKey.matchesBearer(header)
            Door.SESSION -> turnKey.matchesBearer(header)
        }
        if (!authorized) {
            call.respondText(
                buildJsonObject { put("error", "unauthorized") }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return
        }
        Cancellables.runCatchingBestEffort { block() }.onFailure { routeFailure.answer(call, it) }
    }
}
