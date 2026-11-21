// NEW: the one shape a control mount takes on the HTTP host: it registers its rows on the routing block, behind the
// one ControlGuard. A mount that owns a running resource is also a BoundResource, started and stopped by the server.
//
// Nov 21, 2026: and the mounts that stand on the console's ports and the guard alone (StandaloneMounts), which the
// control server takes as one list, so that a capability of that kind is added here and not in the server's file.
package splice.app.control.mount

import io.ktor.server.routing.Route
import splice.app.control.ConsolePorts
import splice.app.control.server.ServerMount
import splice.app.modes.ModesMount

internal fun interface ControlMount {
    fun register(route: Route)
}

/** The capabilities with nothing of the server's own to wire: what `splice console <action>` hands the open console
 *  to do and the page's answer (ConsolePilot, Oct 16, 2026), the modes and their switches (modes/README.md, Nov 19,
 *  2026), and a server's keys, people, usage ledger and budgets, which `splice server` asks for
 *  (docs/specs/server-mode.md, Nov 15, 2026). [ports] is read by each mount at call time, never here.
 *
 *  [rows] register among splice's own routes, in this order. [last] registers after every other route, the MCP host's
 *  included: each mode's own routes are checked against the routes already there, and a path taken refuses the
 *  daemon. */
internal class StandaloneMounts(ports: ConsolePorts, guard: ControlGuard) {
    private val consolePilot = ConsolePilotMount(guard)
    private val modes = ModesMount(ports, guard)
    private val serverMode = ServerMount(ports, guard)

    val rows: List<ControlMount> = listOf(
        ControlMount(consolePilot::register),
        ControlMount(modes::register),
        ControlMount(serverMode::register),
    )
    val last: ControlMount = ControlMount(modes::registerRoutes)
}
