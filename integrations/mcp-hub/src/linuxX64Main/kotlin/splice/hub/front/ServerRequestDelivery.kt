package splice.hub.front

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class ServerRequestOutstanding(
    val holder: Holder,
    val generation: String,
    val id: JsonElement?,
    var session: Session? = null,
    var response: String? = null,
    var delivery: String? = null,
) {
    val answering: Boolean get() = delivery != null
}

internal class ServerRequestLedger {
    val requests = HashMap<String, ServerRequestOutstanding>()
    private var next = 0L

    fun retain(holder: Holder, id: JsonElement): String? {
        val generation = holder.generation ?: return null
        val key = nextKey()
        requests[key] = ServerRequestOutstanding(holder, generation, id)
        return key
    }

    fun bind(key: String, session: Session, persistence: HolderPersistence): Boolean {
        val asked = requests[key]?.takeUnless(ServerRequestOutstanding::answering) ?: return false
        val prior = asked.session
        asked.session = session
        if (persistence.checkpoint()) return true
        asked.session = prior
        return false
    }

    fun restore(
        saved: JsonObject?,
        holder: (String) -> Holder,
        sessions: Map<String, Session>,
        remembered: (Holder) -> Unit,
    ) {
        saved?.forEach { (key, value) ->
            (value as? JsonObject)?.let { body ->
                restored(key, body, holder, sessions)?.let { asked ->
                    requests[key] = asked
                    remembered(asked.holder)
                }
            }
        }
    }

    fun state(): JsonObject = JsonObject(requests.mapValues { (_, asked) -> record(asked) })

    fun current(key: String): ServerRequestOutstanding? =
        requests[key]?.takeIf { it.holder.generation == it.generation }

    fun settles(key: String, asked: ServerRequestOutstanding): Boolean {
        if (requests[key] !== asked) return false
        if (asked.holder.generation != asked.generation) return false
        return asked.delivery == RESPONSE_WRITE_UNCERTAIN
    }

    fun removeGeneration(holder: Holder, generation: String) {
        requests.entries
            .filter { (_, asked) -> asked.holder === holder && asked.generation == generation }
            .forEach { (key, _) -> requests.remove(key) }
    }

    private fun restored(
        key: String,
        body: JsonObject,
        holder: (String) -> Holder,
        sessions: Map<String, Session>,
    ): ServerRequestOutstanding? {
        val socket = Rpc.str(body, SOCKET) ?: return null
        val generation = Rpc.str(body, GENERATION) ?: return null
        restored(key)
        val response = Rpc.str(body, RESPONSE)
        return ServerRequestOutstanding(
            holder(socket),
            generation,
            body[RESPONSE_ID],
            Rpc.str(body, SESSION)?.let(sessions::get),
            response,
            delivery(body, response),
        )
    }

    private fun record(asked: ServerRequestOutstanding): JsonObject = buildJsonObject {
        put(SOCKET, asked.holder.socket)
        put(GENERATION, asked.generation)
        asked.id?.let { put(RESPONSE_ID, it) }
        asked.session?.let { put(SESSION, it.id) }
        put(ANSWERING, asked.answering)
        asked.response?.let { put(RESPONSE, it) }
        asked.delivery?.let { put(RESPONSE_STATUS, it) }
    }

    private fun delivery(body: JsonObject, response: String?): String? = when {
        response == null -> legacyDelivery(body)
        Rpc.str(body, RESPONSE_STATUS) == RESPONSE_PREPARED -> RESPONSE_PREPARED
        else -> RESPONSE_WRITE_UNCERTAIN
    }

    private fun legacyDelivery(body: JsonObject): String? {
        val status = Rpc.str(body, RESPONSE_STATUS)
        val answering = Rpc.str(body, ANSWERING)
        return if (status != null || answering == "true") RESPONSE_LEGACY_UNCERTAIN else null
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

internal class ServerRequestDelivery(
    private val ledger: ServerRequestLedger,
    private val persistence: HolderPersistence,
) {
    fun answer(session: Session, msg: JsonObject, id: JsonElement) {
        val key = (id as? JsonPrimitive)?.contentOrNull ?: return
        ledger.requests[key]
            ?.takeIf { it.session === session }
            ?.id
            ?.let { reply(key, Rpc.with(msg, Rpc.ID, it)) }
    }

    fun retire(session: Session, reason: String) {
        ledger.requests
            .filterValues { it.session === session }
            .keys
            .forEach { key -> ledger.requests[key]?.id?.let { reply(key, Rpc.error(it, reason)) } }
    }

    fun reply(key: String, text: String) {
        ledger.current(key)?.takeIf { prepared(it, text) }?.let { send(key, it) }
    }

    fun remembers(holder: Holder) {
        holder.resumeReverseResponses = { generation -> resume(holder, generation) }
    }

    private fun resume(holder: Holder, generation: String) {
        ledger.requests
            .filterValues { resumable(it, holder, generation) }
            .forEach { (key, asked) -> send(key, asked) }
    }

    private fun resumable(
        asked: ServerRequestOutstanding,
        holder: Holder,
        generation: String,
    ): Boolean {
        if (asked.holder !== holder) return false
        if (asked.generation != generation) return false
        return asked.delivery == RESPONSE_PREPARED
    }

    private fun prepared(asked: ServerRequestOutstanding, text: String): Boolean = when (asked.delivery) {
        null -> stage(asked, text)
        RESPONSE_PREPARED -> asked.response != null
        else -> false
    }

    private fun stage(asked: ServerRequestOutstanding, text: String): Boolean {
        asked.response = text
        asked.delivery = RESPONSE_PREPARED
        if (persistence.checkpoint()) return true
        asked.response = null
        asked.delivery = null
        return false
    }

    private fun send(key: String, asked: ServerRequestOutstanding) {
        val link = asked.holder.link ?: return
        if (writing(asked)) {
            asked.response?.let { response ->
                link.send(response, HolderWritten { settled(key, asked) })
            }
        }
    }

    /** A persisted write attempt may have reached the holder only partly, so only exact exit can settle it. */
    private fun writing(asked: ServerRequestOutstanding): Boolean {
        if (asked.delivery != RESPONSE_PREPARED) return false
        asked.delivery = RESPONSE_WRITE_UNCERTAIN
        if (persistence.checkpoint()) return true
        asked.delivery = RESPONSE_PREPARED
        return false
    }

    private fun settled(key: String, asked: ServerRequestOutstanding) {
        if (ledger.settles(key, asked)) {
            ledger.requests.remove(key)
            persistence.changed()
        }
    }
}

private const val RESPONSE_ID = "response_id"
private const val SESSION = "session"
private const val ANSWERING = "answering"
private const val RESPONSE = "response"
private const val RESPONSE_STATUS = "response_status"
private const val RESPONSE_PREPARED = "prepared"
private const val RESPONSE_WRITE_UNCERTAIN = "write_uncertain"
private const val RESPONSE_LEGACY_UNCERTAIN = "legacy_write_uncertain"
