// NEW: V4-319 — the console's reads of a head's live turns, and its stop.
//
// GET  /api/heads/{head}/turns/live      -> {turns: [{id, session, model, idle_ms, seen_output, resumes,
//                                           retries, will_resume, stopped}]}
// POST /api/heads/{head}/turns/{id}/stop -> {}
// POST /api/heads/{head}/holds/try-now[?login=] -> {probed} (slice 5: a held login probed at once)
//
// Read in process from the registry HeadServerFactory fills as it builds each head (LiveTurnsByHead),
// as the wire tap's route reads its ring. An unknown head is a 400 naming it, never a 404: the console
// reads 404 on a route as a route this daemon does not serve. A turn that is not live, because it
// ended or never ran here, IS a 404 on the stop: the turn is what is not found, and the console says so.
package splice.head.turn

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.head.GateHealth
import splice.core.util.JsonWire
import splice.head.TurnsHeadLookup
import splice.http.JsonReply

internal const val LIVE_TURNS_UNWIRED =
    "the daemon wired no live-turn registry; a head's turns cannot be read or stopped"

/** The registry, read per request because the control plane is handed it after construction. */
public fun interface LiveTurnsSource {
    public operator fun invoke(): LiveTurnsByHead?
}

/** A head's gate as its health snapshot reads it now: why each admitted turn is where it is, and who waits. */
public fun interface LiveGateSource {
    public operator fun invoke(head: String): GateHealth?
}

public class LiveTurnsRoutes(
    private val heads: TurnsHeadLookup,
    private val registry: LiveTurnsSource,
    private val gates: LiveGateSource = LiveGateSource { null },
) {

    /** Each live turn, with the gate's phase, age since admission and compaction flag when its slot names it, so the
     *  console can say why a turn is held (connecting, streaming, a compaction), and under `waiting` each turn the
     *  gate has queued, oldest first, with its session and how long it has waited. */
    public fun live(head: String): JsonReply = resolved(head) { key, turns ->
        val gate = gates(key)
        val slots = gate?.live.orEmpty().filter { it.turnId != null }.associateBy { it.turnId }
        val body = buildJsonObject {
            putJsonArray("turns") {
                turns.list().forEach { turn ->
                    add(
                        buildJsonObject {
                            put("id", turn.id)
                            put("session", turn.session)
                            put("model", turn.model)
                            put("idle_ms", turn.silence.idleMs)
                            put("seen_output", turn.silence.seenOutput)
                            put("resumes", turn.silence.resumes)
                            put("retries", turn.silence.retries)
                            put("will_resume", turn.silence.willResume)
                            put("stopped", turn.stopped)
                            held(this, turn.state)
                            slots[turn.id]?.let { slot ->
                                put("phase", slot.phase.name.lowercase())
                                put("age_ms", slot.ageMs)
                                put("compact", slot.compact)
                            }
                        },
                    )
                }
            }
            // Turns the gate has not let in yet: a quiet agent that is only waiting its turn, and since when.
            putJsonArray("waiting") {
                gate?.waiting.orEmpty().forEach { wait ->
                    add(
                        buildJsonObject {
                            put("session", wait.session)
                            put("waited_ms", wait.waitedMs)
                        },
                    )
                }
            }
        }
        JsonReply(HttpStatusCode.OK, JsonWire.string(body))
    }

    /** The console's try-now (failure-handling slice 5): the turn held longest on [login], or on every login when null,
     *  probes at once. Answers how many logins it probed; none is a 404, since nothing on the head is held there. */
    public fun tryNow(head: String, login: String?): JsonReply = resolved(head) { key, turns ->
        val probed = turns.holds.tryNow(login?.ifBlank { null })
        if (probed > 0) {
            JsonReply(HttpStatusCode.OK, JsonWire.string(buildJsonObject { put("probed", probed) }))
        } else {
            refuse(HttpStatusCode.NotFound, "no turn is held on head $key" + login?.let { " for login $it" }.orEmpty())
        }
    }

    public fun stop(head: String, id: String): JsonReply = resolved(head) { key, turns ->
        if (turns.stop(id)) {
            JsonReply(HttpStatusCode.OK, "{}")
        } else {
            refuse(HttpStatusCode.NotFound, "no live turn $id on head $key: it has ended")
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

    /** A held turn's reset, login, cause and heap share; nothing for a running one. */
    private fun held(json: JsonObjectBuilder, state: TurnState) {
        if (state !is TurnState.Held) return
        json.put("held", true)
        json.put("held_cause", state.cause)
        json.put("held_login", state.login)
        json.put("held_since", state.since.toString())
        state.until?.let { json.put("held_until", it.toString()) }
        json.put("heap_bytes", state.heapBytes)
    }

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, JsonWire.string(buildJsonObject { put("error", message) }))
}
