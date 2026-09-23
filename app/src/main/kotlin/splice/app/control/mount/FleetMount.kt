// NEW: LAYOUT-01 — the surface the control plane itself owns: liveness, the console page, the status
// payload, the head list with its per-head actions, and each head's log tail.
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.DashboardPage
import splice.app.control.api.ControlAudit
import splice.app.control.api.ControlPayloads
import splice.app.control.api.HeadResolver
import splice.app.control.api.fleet.HeadRoutes
import splice.heads.HeadStatusListing
import splice.heads.ListHeads

// why: the log lines /api/logs/{head} returns when the request names no ?tail — the recent end of the
// file the console shows first, never the whole file.
private const val DEFAULT_LOG_TAIL = 200

internal class FleetMount(
    private val payloads: ControlPayloads,
    resolver: HeadResolver,
    audit: ControlAudit,
    private val dashboardHtml: DashboardPage,
    private val guard: ControlGuard,
) {
    private val headRoutes = HeadRoutes(resolver, payloads, audit)
    private val listHeads = ListHeads(HeadStatusListing(resolver::headStatuses))

    fun register(route: Route) {
        // Unauthenticated liveness probe: the launch shim polls this to tell a running
        // daemon from a cold start (it must NOT need the mgmt-key). No head/config detail.
        route.get("/health") { call.respondText(payloads.controlHealthJson(), ContentType.Application.Json) }
        route.get("/") { call.respondText(dashboardHtml(), ContentType.Text.Html) }
        route.get("/dashboard") { call.respondText(dashboardHtml(), ContentType.Text.Html) }
        route.get("/api/status") { guard.guarded(call) { ControlReplies.respond(call, payloads.statusJson()) } }
        route.get("/api/heads") { guard.guarded(call) { listHeads.handle(call) } }
        route.post("/api/heads/{head}/{action}") { guard.guarded(call) { headRoutes.headAction(call) } }
        route.get("/api/logs/{head}") {
            guard.guarded(call) { headRoutes.logsJson(call, ControlReplies.tail(call, DEFAULT_LOG_TAIL)) }
        }
    }
}
