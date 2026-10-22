package splice.hub.front

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A server request stays charged until its whole response leaves the front or its exact generation exits. */
class ServerRequests(private val changed: () -> Unit) {
    private data class Outstanding(
        val holder: Holder,
        val generation: String?,
        val id: JsonElement,
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

    fun retain(holder: Holder, id: JsonElement): String {
        val key = "s:${++next}"
        requests[key] = Outstanding(holder, holder.generation, id)
        return key
    }

    fun bind(key: String, session: Session) {
        requests[key]?.session = session
    }

    fun answer(session: Session, msg: JsonObject, id: JsonElement) {
        val key = (id as? JsonPrimitive)?.contentOrNull ?: return
        val asked = requests[key]?.takeIf { it.session === session } ?: return
        reply(key, Rpc.with(msg, Rpc.ID, asked.id))
    }

    fun reply(key: String, text: String) {
        val asked = requests[key]?.takeUnless { it.answering } ?: return
        val link = asked.holder.link ?: return
        if (asked.generation == null || asked.holder.generation != asked.generation) return
        asked.answering = true
        link.send(
            text,
            HolderWritten {
                if (requests[key] === asked && asked.holder.generation == asked.generation) {
                    requests.remove(key)
                    changed()
                }
            },
        )
    }

    fun exited(holder: Holder, generation: String) {
        requests.entries
            .filter { (_, asked) -> asked.holder === holder && asked.generation == generation }
            .forEach { (key, _) -> requests.remove(key) }
    }
}
