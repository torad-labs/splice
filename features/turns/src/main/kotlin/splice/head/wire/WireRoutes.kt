// NEW: V4-239 — GET /api/heads/{head}/wire[?last=N]: what `splice wire <head> [--last N]` prints, for
// the console. The verb asks the head's own port (GET /wire, HeadEngine); the control plane holds no
// head port or key to ask with, so it reads the SAME tap in process, through the registry
// HeadServerFactory fills as it builds each head. The payload is the one the head serves:
// WireTap.json, `{key, keep, records: [{ts, session, model, compact, body}]}`, oldest first.
//
// OFF IS NOT EMPTY. A head whose operator named no count has no tap at all, and the route answers 409
// with the sentence the head's own route answers ([WIRE_TAP_OFF]), so an empty list can only ever mean
// the tap is on and nothing has left the head since the daemon started. Never 404: the console reads
// 404 as a route this daemon does not serve.
package splice.head.wire

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.head.TurnsHeadLookup
import splice.http.JsonReply
import java.util.concurrent.ConcurrentHashMap

/** The body of a wire read on a head whose tap is off, the head's GET /wire and the console's alike. */
internal const val WIRE_TAP_OFF =
    """{"error":"wire tap is off for head KEY: set [heads.KEY.overrides] wireTap = N (bodies to keep) and restart"}"""

internal const val WIRE_TAPS_UNWIRED = "the daemon wired no wire taps; /api/heads/{head}/wire cannot read them"

/** A `last` query parameter that is not a count, on the wire and trace reads alike. */
internal const val BAD_LAST = "last must be a whole number above zero"

/** Every head's wire tap, by head key: the daemon's ONE registry, filled as each head is built and read
 *  by GET /api/heads/{head}/wire. A head built with no tap has no entry, so a rebuild that turns the
 *  tap off takes the old one out. */
public class WireTaps {
    private val taps = ConcurrentHashMap<String, WireTap>()

    /** [tap] is the one [head] was just built with, or null when it was built with none. */
    public fun put(head: String, tap: WireTap?) {
        if (tap == null) taps.remove(head) else taps[head] = tap
    }

    public fun of(head: String): WireTap? = taps[head]
}

/** The registry, read per request because the control plane is handed it after construction. */
public fun interface WireTapsSource {
    public operator fun invoke(): WireTaps?
}

public class WireRoutes(private val heads: TurnsHeadLookup, private val taps: WireTapsSource) {

    /** [last] is the `last` query parameter: absent or blank keeps the whole ring, as the verb does. */
    public fun read(head: String, last: String?): JsonReply {
        val key = heads.byName(head).firstOrNull()?.key
        val asked = last?.takeIf { it.isNotBlank() }
        val count = if (asked == null) 0 else asked.toIntOrNull()?.takeIf { it > 0 }
        val registry = taps()
        return when {
            key == null -> refuse(HttpStatusCode.BadRequest, "unknown head: $head")
            count == null -> refuse(HttpStatusCode.BadRequest, BAD_LAST)
            registry == null -> refuse(HttpStatusCode.ServiceUnavailable, WIRE_TAPS_UNWIRED)
            else -> tapped(key, registry.of(key), count)
        }
    }

    private fun tapped(key: String, tap: WireTap?, last: Int): JsonReply =
        if (tap == null) {
            JsonReply(HttpStatusCode.Conflict, WIRE_TAP_OFF.replace("KEY", key))
        } else {
            JsonReply(HttpStatusCode.OK, tap.json(key, last))
        }

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
