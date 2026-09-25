// NEW: LAYOUT-01 — the daemon's own lifecycle on the control plane: the drain-and-stop, the restart the
// host unit completes, and the upgrade status (features/lifecycle). V4-220: the restart waits for the
// compactions the heads' gates hold (RestartAfterCompactions), read in-process off each head's health.
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.api.ControlPayloads
import splice.core.util.LogSink
import splice.lifecycle.restart.CompactionsInFlight
import splice.lifecycle.restart.DaemonRoutes
import splice.lifecycle.restart.RestartAfterCompactions
import splice.lifecycle.restart.ShutdownDaemon
import splice.lifecycle.upgrade.CompactionSlot
import splice.lifecycle.upgrade.UpgradeRoute
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers

/** [ports] is read at CALL time: ControlPlane assigns [ConsolePorts.supervised] and
 *  [ConsolePorts.upgrade] after the server is constructed. */
internal class LifecycleMount(
    private val payloads: ControlPayloads,
    private val shutdownDaemon: ShutdownDaemon,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    heads: Map<String, ManagedHead>,
    log: LogSink,
) {
    private val compactions = CompactionsInFlight {
        heads.flatMap { (key, managed) ->
            managed.head.healthSnapshot().gate.live.filter { it.compact }.map { CompactionSlot(key, it.ageMs) }
        }
    }
    private val restart = RestartAfterCompactions(compactions, LifecycleScope(ProcessDispatchers().background()), log)
    private val daemonRoutes = DaemonRoutes(restart)
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
        route.get("/api/daemon/restart") { guard.guarded(call) { daemonRoutes.statusJson(call) } }
        route.get("/api/upgrade") { guard.guarded(call) { upgradeRoute.upgradeJson(call, ports.upgrade) } }
    }
}
