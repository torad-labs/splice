// NEW: LAYOUT-01 — the management-key check every control route runs behind, lifted out of ControlServer
// so each capability's mount guards its own rows with the one check rather than a copy of it.
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.MgmtRoute
import splice.client.mcp.McpAccessKey
import splice.core.config.MgmtKey

/** Runs [MgmtRoute] only after the bearer matched the management key. `mcp = true` also admits the
 *  MCP access key, which only the /mcp/{name} routes accept; everything else is the management key's. */
internal class ControlGuard(private val mgmtKey: MgmtKey) {
    private val mcpAccessKey = McpAccessKey(mgmtKey::get)

    suspend fun guarded(call: ApplicationCall, mcp: Boolean = false, block: MgmtRoute) {
        val header = call.request.headers["Authorization"]
        val authorized = mgmtKey.matchesBearer(header) || (mcp && mcpAccessKey.matchesBearer(header))
        if (!authorized) {
            call.respondText(
                buildJsonObject { put("error", "unauthorized") }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return
        }
        block()
    }
}
