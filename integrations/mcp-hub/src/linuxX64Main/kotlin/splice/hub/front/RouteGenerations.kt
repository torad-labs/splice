package splice.hub.front

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Attests legacy routes by their retained socket, without resolving a changed launch configuration. */
internal class RouteGenerations(
    private val daemon: Daemon,
    private val timers: Timers,
    private val clock: Clock,
    private val changed: () -> Unit,
) {
    fun refresh(route: Route) {
        val socket = route.socket ?: return
        attest(socket, clock.now() + RESOLVE_BUDGET) { status, body -> refreshed(route, socket, status, body) }
    }

    private fun attest(socket: String, until: Long, then: (Int, String) -> Unit) {
        val body = Rpc.encode(buildJsonObject { put(SOCKET, socket) })
        daemon.call(ATTEST, body) { status, text ->
            val unreachable = status == 0 || status == UNAVAILABLE
            if (unreachable && clock.now() < until) {
                timers.at(clock.now() + RETRY) { attest(socket, until, then) }
            } else {
                then(status, text)
            }
        }
    }

    private fun refreshed(route: Route, socket: String, status: Int, body: String) {
        if (status != OK || route.socket != socket) return
        val attested = JsonText.objectOrNull(body)
        if (Rpc.str(attested, SOCKET) != socket) return
        route.generation = Rpc.str(attested, GENERATION) ?: return
        changed()
    }
}
