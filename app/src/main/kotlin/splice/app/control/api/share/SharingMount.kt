// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). The Sharing page's rows (SharingRoutes), every one
// behind the management door: what this machine shares and with whom, and the machines it joined. It lives beside its
// routes rather than in control.mount, which the concentration ratchet holds at the files it had.
package splice.app.control.api.share

import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import splice.app.control.ConsolePorts
import splice.app.control.mount.ControlGuard

/** [ports] is read at CALL time: ConsolePortsWiring assigns [ConsolePorts.sharing] after the server is constructed. */
internal class SharingMount(ports: ConsolePorts, private val guard: ControlGuard) {
    private val routes = SharingRoutes(SharingWired { ports.sharing })
    private val remotes = RemoteRoutes()

    fun register(route: Route) {
        route.get("/api/share") { guard.guarded(call) { routes.read().send(call) } }
        route.put("/api/share") { guard.guarded(call) { routes.write(call).send(call) } }
        route.delete("/api/share/machines/{name}") {
            guard.guarded(call) { routes.revoke(call.parameters["name"].orEmpty()).send(call) }
        }
        route.post("/api/remotes/scan") { guard.guarded(call) { remotes.scan().send(call) } }
        route.post("/api/remotes") { guard.guarded(call) { remotes.import(call).send(call) } }
        route.post("/api/remotes/{name}/sync") {
            guard.guarded(call) { remotes.sync(call.parameters["name"].orEmpty()).send(call) }
        }
        route.delete("/api/remotes/{name}") {
            guard.guarded(call) { remotes.remove(call.parameters["name"].orEmpty()).send(call) }
        }
    }
}
