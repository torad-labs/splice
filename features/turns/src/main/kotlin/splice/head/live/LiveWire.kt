// NEW 2026-11-19 (reply-stream): the events a follower of GET /api/sessions/{id}/live is sent, each already on the
// wire as `event: <name>\ndata: <one JSON line>\n\n`. Every field is always present; a value that does not apply is
// JSON null. Built once per event and offered to every follower of the session as the same string.
package splice.head.live

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.JsonWire

/** The one place the live route's event names and fields are spelled. */
internal object LiveWire {
    /** The error an ending carries when the response ended with neither a stop nor an error event. */
    const val CUT_ENDING: String = "cut"

    fun hello(session: String, live: List<String>): String = event("hello") {
        put("session", session)
        putJsonArray("live") { live.forEach { add(JsonPrimitive(it)) } }
    }

    fun turn(reply: LiveReply, message: String?): String = event("turn") {
        put("turn", reply.id)
        put("message", message)
        put("agent", reply.facts.agent)
        put("model", reply.facts.model)
        put("head", reply.facts.head)
        put("compact", reply.facts.compact)
        put("at", reply.at)
    }

    fun text(turn: String, index: Int, delta: String): String = delta("text", turn, index, delta)

    fun thinking(turn: String, index: Int, delta: String): String = delta("thinking", turn, index, delta)

    fun tool(turn: String, index: Int, id: String?, name: String?): String = event("tool") {
        put("turn", turn)
        put("index", index)
        put("id", id)
        put("name", name)
    }

    fun end(turn: String, stopReason: String?, error: String?): String = event("end") {
        put("turn", turn)
        put("stop_reason", stopReason)
        put("error", error)
    }

    private fun delta(name: String, turn: String, index: Int, delta: String): String = event(name) {
        put("turn", turn)
        put("index", index)
        put("delta", delta)
    }

    private inline fun event(name: String, fields: JsonObjectBuilder.() -> Unit): String =
        "event: $name\ndata: ${JsonWire.string(buildJsonObject(fields))}\n\n"
}
