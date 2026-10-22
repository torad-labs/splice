package splice.hub.front

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A server request stays charged until its whole response leaves the front or its exact generation exits. */
class ServerRequests(
    private val changed: () -> Unit,
    private val checkpoint: () -> Boolean,
) {
    private data class Outstanding(
        val holder: Holder,
        val generation: String,
        val id: JsonElement?,
        var session: Session? = null,
        var answering: Boolean = false,
    )

    private val requests = HashMap<String, Outstanding>()
    private var next = 0L

    val size: Int get() = requests.size
    val busy: Set<String> get() = requests.values.map { it.holder.socket }.toSet()

    fun count(socket: String?): Int = requests.values.count { it.holder.socket == socket }

    fun awaitsExit(holder: Holder, generation: String): Boolean =
        requests.values.any { it.holder === holder && it.generation == generation }

    val retainedGenerations: List<Pair<Holder, String>>
        get() = requests.values.map { it.holder to it.generation }.distinct()

    /** Keeps the server request before it can reach a session. A failed checkpoint leaves it charged but unforwarded. */
    fun retain(holder: Holder, id: JsonElement): String? {
        val generation = holder.generation ?: return null
        val key = nextKey()
        requests[key] = Outstanding(holder, generation, id)
        return key.takeIf { checkpoint() }
    }

    /** Binds a session durably before the front sends that session the rewritten request id. */
    fun bind(key: String, session: Session): Boolean {
        val asked = requests[key]?.takeUnless { it.answering } ?: return false
        val prior = asked.session
        asked.session = session
        if (checkpoint()) return true
        asked.session = prior
        return false
    }

    fun restore(saved: JsonObject?, holder: (String) -> Holder, sessions: Map<String, Session>) {
        saved?.forEach { (key, value) ->
            val body = value as? JsonObject ?: return@forEach
            val socket = Rpc.str(body, SOCKET) ?: return@forEach
            val generation = Rpc.str(body, GENERATION) ?: return@forEach
            requests[key] = Outstanding(
                holder(socket),
                generation,
                body[RESPONSE_ID],
                Rpc.str(body, SESSION)?.let(sessions::get),
                Rpc.str(body, ANSWERING) == "true",
            )
            restored(key)
        }
    }

    fun state(): JsonObject =
        JsonObject(
            requests.mapValues { (_, asked) ->
                buildJsonObject {
                    put(SOCKET, asked.holder.socket)
                    put(GENERATION, asked.generation)
                    asked.id?.let { put(RESPONSE_ID, it) }
                    asked.session?.let { put(SESSION, it.id) }
                    put(ANSWERING, asked.answering)
                }
            },
        )

    fun answer(session: Session, msg: JsonObject, id: JsonElement) {
        val key = (id as? JsonPrimitive)?.contentOrNull ?: return
        val asked = requests[key]?.takeIf { it.session === session } ?: return
        asked.id?.let { reply(key, Rpc.with(msg, Rpc.ID, it)) }
    }

    /** A retired session cannot answer later. Its exact server request gets an explicit response when it is recoverable. */
    fun retire(session: Session, reason: String) {
        requests.entries
            .filter { (_, asked) -> asked.session === session }
            .map { (key, _) -> key }
            .forEach { key -> requests[key]?.id?.let { reply(key, Rpc.error(it, reason)) } }
    }

    fun reply(key: String, text: String) {
        requests[key]
            ?.takeUnless { it.answering }
            ?.takeIf { it.holder.generation == it.generation }
            ?.let { asked -> send(key, asked, text) }
    }

    private fun send(key: String, asked: Outstanding, text: String) {
        val link = asked.holder.link ?: return
        asked.answering = true
        if (!checkpoint()) {
            asked.answering = false
            return
        }
        link.send(text, HolderWritten { settled(key, asked) })
    }

    private fun settled(key: String, asked: Outstanding) {
        if (requests[key] === asked && asked.holder.generation == asked.generation) {
            requests.remove(key)
            changed()
        }
    }

    fun exited(holder: Holder, generation: String) {
        requests.entries
            .filter { (_, asked) -> asked.holder === holder && asked.generation == generation }
            .forEach { (key, _) -> requests.remove(key) }
    }

    private fun nextKey(): String {
        var key: String
        do {
            key = "s:${++next}"
        } while (key in requests)
        return key
    }

    private fun restored(key: String) {
        key.removePrefix("s:").toLongOrNull()?.let { next = maxOf(next, it) }
    }
}

private const val RESPONSE_ID = "response_id"
private const val SESSION = "session"
private const val ANSWERING = "answering"
