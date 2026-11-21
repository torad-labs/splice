// NEW: door-sessions (Nov 20, 2026). How a session's route reads on the wire, one shape for `GET /api/sessions` and
// `splice sessions --json`: the route's name (`head`, `door`, `direct` or `unknown`), and a door session's `door`
// object: the command its launch recorded, the door's address it runs on, and the machine that serves it, null for
// this machine's own door. The word `door` is core's DOOR_SEGMENT, which no head key may take.
package splice.sessions.registry

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.topology.DOOR_SEGMENT
import splice.core.topology.LaunchDoor

/** A [SessionRoute] as the sessions wire carries it. */
internal object SessionRouteWire {
    /** The route's wire name: `head` beside a real head key, `door` for a session on a door, `direct` for one that
     *  never went through splice, `unknown` for one splice cannot place. */
    fun name(route: SessionRoute): String = when (route) {
        is SessionRoute.Head -> "head"
        is SessionRoute.Door -> DOOR_SEGMENT
        SessionRoute.Direct -> "direct"
        SessionRoute.Unknown -> "unknown"
    }

    /** A door session's [door] as its row's `door` object. */
    fun door(door: LaunchDoor): JsonObject = buildJsonObject {
        put("command", door.command)
        put("address", door.address)
        put("machine", door.machine)
    }
}
