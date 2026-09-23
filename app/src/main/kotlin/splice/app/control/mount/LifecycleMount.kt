// NEW: LAYOUT-01 — the daemon's own lifecycle on the control plane: the drain-and-stop, the restart the
// host unit completes, and the upgrade status (features/lifecycle).
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.ConsolePorts
import splice.app.control.api.ControlPayloads
import splice.lifecycle.restart.DaemonRoutes
import splice.lifecycle.restart.ShutdownDaemon
import splice.lifecycle.upgrade.UpgradeRoute

/** [ports] is read at CALL time: ControlPlane assigns [ConsolePorts.supervised] and
 *  [ConsolePorts.upgrade] after the server is constructed. */
internal class LifecycleMount(
    private val payloads: ControlPayloads,
    private val shutdownDaemon: ShutdownDaemon,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val daemonRoutes = DaemonRoutes()
    private val upgradeRoute = UpgradeRoute()

    fun register(route: Route) {
        route.post("/api/daemon/shutdown") {
            guard.guarded(call) {
                call.respondText(payloads.okJson(), ContentType.Application.Json, HttpStatusCode.Accepted)
                shutdownDaemon()
            }
        }
        // The same drain POST /api/daemon/shutdown requests, offered as a restart because the host
        // unit brings the daemon back. REFUSED when nothing would, and the refusal takes no drain.
        route.post("/api/daemon/restart") {
            guard.guarded(call) { daemonRoutes.restartJson(call, shutdownDaemon, ports.supervised) }
        }
        route.get("/api/upgrade") { guard.guarded(call) { upgradeRoute.upgradeJson(call, ports.upgrade) } }
    }
}
