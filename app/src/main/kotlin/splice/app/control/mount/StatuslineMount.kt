// NEW: session-authenticated status-line routes own their session snapshots and legacy-client response.
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.core.config.ConfigService
import splice.core.session.SessionLines
import splice.core.version.ClientVersionTracker
import splice.usage.UsageHeadLookup
import splice.usage.statusline.StatuslineRoute

/** What a pre-0.4.0 session's status line prints when it sends no session bearer. */
private const val STATUSLINE_RELAUNCH = "splice updated: relaunch this session for its status line"

internal class StatuslineMount(
    heads: UsageHeadLookup,
    config: ConfigService,
    clientVersions: ClientVersionTracker,
    private val guard: ControlGuard,
) {
    /** Each session's latest status-line figures, shared with the manager's state. */
    val lines = SessionLines()
    private val statusline = StatuslineRoute(heads, config, clientVersions, lines)

    fun register(route: Route) {
        // A session's turn key opens these routes, not the management-only door of usage APIs.
        route.post("/statusline/{head}") {
            guard.guarded(call, Door.SESSION, STATUSLINE_RELAUNCH) { statusline.statusline(call) }
        }
        route.get("/statusline/{head}") {
            guard.guarded(call, Door.SESSION, STATUSLINE_RELAUNCH) { statusline.statusline(call) }
        }
    }
}
