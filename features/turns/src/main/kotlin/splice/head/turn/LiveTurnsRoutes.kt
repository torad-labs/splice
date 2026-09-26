// NEW: V4-319 — the console's reads of a head's live turns, and its stop.
//
// GET  /api/heads/{head}/turns/live      -> {head, turns: [{id, session, model, compact, age_ms, stopped}]}
// POST /api/heads/{head}/turns/{id}/stop -> {stopped: true, head, session}
//
// Read in process from the registry HeadServerFactory fills as it builds each head (LiveTurnsByHead),
// as the wire tap's route reads its ring. An unknown head is a 400 naming it, never a 404: the console
// reads 404 on a route as a route this daemon does not serve. A turn that is not live, because it
// ended or never ran here, IS a 404 on the stop: the turn is what is not found, and the console says so.
package splice.head.turn

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.head.TurnsHeadLookup
import splice.http.JsonReply

internal const val LIVE_TURNS_UNWIRED =
    "the daemon wired no live-turn registry; a head's turns cannot be read or stopped"

/** The registry, read per request because the control plane is handed it after construction. */
public fun interface LiveTurnsSource {
    public operator fun invoke(): LiveTurnsByHead?
}

public class LiveTurnsRoutes(private val heads: TurnsHeadLookup, private val registry: LiveTurnsSource) {

    public fun live(head: String): JsonReply = resolved(head) { key, turns ->
        val body = buildJsonObject {
            put("head", key)
            putJsonArray("turns") {
                turns.list().forEach { turn ->
                    add(
                        buildJsonObject {
                            put("id", turn.id)
                            put("session", turn.session)
                            put("model", turn.model)
                            put("compact", turn.compact)
                            put("age_ms", turn.ageMs)
                            put("stopped", turn.stopped)
                        },
                    )
                }
            }
        }
        JsonReply(HttpStatusCode.OK, body.toString())
    }

    public fun stop(head: String, id: String): JsonReply = resolved(head) { key, turns ->
        val stopped = turns.stop(id)
        if (stopped == null) {
            refuse(HttpStatusCode.NotFound, "no live turn $id on head $key: it has ended")
        } else {
            val body = buildJsonObject {
                put("stopped", true)
                put("head", key)
                put("session", stopped.session)
            }
            JsonReply(HttpStatusCode.OK, body.toString())
        }
    }

    private fun interface Answer {
        fun on(key: String, turns: LiveTurns): JsonReply
    }

    private fun resolved(head: String, answer: Answer): JsonReply {
        val key = heads.byName(head).firstOrNull()?.key
        val byHead = registry()
        val turns = key?.let { byHead?.of(it) }
        return when {
            key == null -> refuse(HttpStatusCode.BadRequest, "unknown head: $head")
            byHead == null -> refuse(HttpStatusCode.ServiceUnavailable, LIVE_TURNS_UNWIRED)
            turns == null -> refuse(HttpStatusCode.ServiceUnavailable, "head $key registered no live turns")
            else -> answer.on(key, turns)
        }
    }

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
