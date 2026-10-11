// NEW: the one shape a control mount takes on the HTTP host: it registers its rows on the routing block, behind the
// one ControlGuard. A mount that owns a running resource is also a BoundResource, started and stopped by the server.
package splice.app.control.mount

import io.ktor.server.routing.Route

internal fun interface ControlMount {
    fun register(route: Route)
}
