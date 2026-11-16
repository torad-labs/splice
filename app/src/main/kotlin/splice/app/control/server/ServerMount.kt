// NEW: server mode (docs/specs/server-mode.md). The control plane's routes on the daemon's server mode, behind the
// management key on loopback, which is how `splice server` reaches the daemon that holds it: its keys, people and
// upstreams (ServerModeRoutes), and its usage ledger, export and budgets (ServerLedgerRoutes). A daemon with no
// server mode answers each of them 409, saying why it has none (ServerModeReplies).
package splice.app.control.server

import io.ktor.server.routing.Route
import splice.app.control.ConsolePorts
import splice.app.control.mount.ControlGuard

internal class ServerMount(ports: ConsolePorts, guard: ControlGuard) {
    private val replies = ServerModeReplies(ports)
    private val mode = ServerModeRoutes(replies, guard)
    private val ledger = ServerLedgerRoutes(replies, guard)

    fun register(route: Route) {
        mode.register(route)
        ledger.register(route)
    }
}
