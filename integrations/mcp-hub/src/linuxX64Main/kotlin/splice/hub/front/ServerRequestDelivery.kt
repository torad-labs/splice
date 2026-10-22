package splice.hub.front

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import splice.hub.holder.HolderProtocol

internal data class ServerResponseState(
    var text: String? = null,
    var delivery: String? = null,
    var durable: Boolean = false,
    var retries: Int = 0,
    var utf8Bytes: Int = 0,
)

internal data class ServerRequestOutstanding(
    val holder: Holder,
    val generation: String,
    val id: JsonElement?,
    var session: Session? = null,
    val response: ServerResponseState = ServerResponseState(),
) {
    val answering: Boolean get() = response.delivery != null
}

private data class PreparedResponse(val link: HolderLink, val text: String)

internal class ServerRequestLedger {
    val requests = HashMap<String, ServerRequestOutstanding>()
    private var next = 0L
    private var retainedResponseBytes = 0L

    fun retain(holder: Holder, id: JsonElement): String? {
        if (requests.size >= MAX_RETAINED_REVERSE || retainedResponseBytes >= MAX_RETAINED_RESPONSE_BYTES) return null
        val generation = holder.generation ?: return null
        val key = nextKey()
        requests[key] = ServerRequestOutstanding(holder, generation, id)
        return key
    }

    fun reserveResponse(asked: ServerRequestOutstanding, text: String, utf8Bytes: Int): Boolean {
        if (retainedResponseBytes + utf8Bytes.toLong() > MAX_RETAINED_RESPONSE_BYTES) return false
        asked.response.text = text
        asked.response.utf8Bytes = utf8Bytes
        retainedResponseBytes += utf8Bytes.toLong()
        return true
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
    ) {
        saved?.forEach { (key, value) ->
            (value as? JsonObject)?.let { body ->
                restored(key, body, holder, sessions)?.let { asked ->
                    requests[key] = asked
                    retainedResponseBytes += asked.response.utf8Bytes.toLong()
                }
            }
        }
    }

    fun state(): JsonObject = JsonObject(requests.mapValues { (_, asked) -> record(asked) })

    fun current(key: String): ServerRequestOutstanding? =
        requests[key]?.takeIf { it.holder.generation == it.generation }

    fun forwarded(holder: Holder, id: JsonElement): Pair<String, Session>? =
        requests.entries.firstOrNull { (_, asked) ->
            asked.holder === holder &&
                asked.generation == holder.generation &&
                asked.id == id &&
                asked.session != null
        }?.let { (key, asked) -> key to requireNotNull(asked.session) }

    fun settles(key: String, asked: ServerRequestOutstanding, checkpoint: () -> Boolean): Boolean {
        val settled = requests[key] === asked &&
            asked.holder.generation == asked.generation &&
            asked.response.delivery == RESPONSE_WRITE_UNCERTAIN
        if (!settled) return false
        requests.remove(key)
        retainedResponseBytes -= asked.response.utf8Bytes.toLong()
        if (checkpoint()) return true
        requests[key] = asked
        retainedResponseBytes += asked.response.utf8Bytes.toLong()
        return false
    }

    fun removeGeneration(holder: Holder, generation: String) {
        requests.entries
            .filter { (_, asked) -> asked.holder === holder && asked.generation == generation }
            .forEach { (key, asked) ->
                requests.remove(key)
                retainedResponseBytes -= asked.response.utf8Bytes.toLong()
            }
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
            ServerResponseState(
                response,
                delivery(body, response),
                response != null,
                Rpc.str(body, RESPONSE_RETRIES)?.toIntOrNull() ?: 0,
                response?.encodeToByteArray()?.size ?: 0,
            ),
        )
    }

    private fun record(asked: ServerRequestOutstanding): JsonObject = buildJsonObject {
        put(SOCKET, asked.holder.socket)
        put(GENERATION, asked.generation)
        asked.id?.let { put(RESPONSE_ID, it) }
        asked.session?.let { put(SESSION, it.id) }
        put(ANSWERING, asked.answering)
        asked.response.text?.let { put(RESPONSE, it) }
        asked.response.delivery?.let { put(RESPONSE_STATUS, it) }
        if (asked.response.retries != 0) put(RESPONSE_RETRIES, asked.response.retries)
    }

    private fun delivery(body: JsonObject, response: String?): String? = when {
        response == null -> {
            val status = Rpc.str(body, RESPONSE_STATUS)
            val answering = Rpc.str(body, ANSWERING)
            if (status != null || answering == "true") RESPONSE_LEGACY_UNCERTAIN else null
        }
        Rpc.str(body, RESPONSE_STATUS) == RESPONSE_PREPARED -> RESPONSE_PREPARED
        else -> RESPONSE_WRITE_UNCERTAIN
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
    fun answer(session: Session, msg: JsonObject, id: JsonElement): Boolean? {
        val key = (id as? JsonPrimitive)?.contentOrNull ?: return null
        val asked = ledger.requests[key]?.takeIf { it.session === session } ?: return null
        return asked.id?.let { original ->
            val response = Rpc.with(msg, Rpc.ID, original)
            if (!asked.answering) {
                reply(key, response)
            } else if (prepared(asked, response)) {
                send(key, asked)
                true
            } else {
                false
            }
        }
    }

    fun retire(session: Session, reason: String) {
        ledger.requests
            .filterValues { it.session === session }
            .keys
            .forEach { key -> ledger.requests[key]?.id?.let { reply(key, Rpc.error(it, reason)) } }
    }

    fun reply(key: String, text: String): Boolean =
        ledger.current(key)?.takeIf { prepared(it, text) }?.let {
            send(key, it)
            true
        } == true

    /** A request never reaches a new client after restart; its exact holder id receives a durable recovery error. */
    fun recover() {
        ledger.requests.keys.forEach { key ->
            ledger.current(key)?.id?.let { id -> reply(key, Rpc.error(id, RECOVERY_INTERRUPTED)) }
        }
    }

    /** A live persistence failure is answered on the exact holder generation rather than waiting for another restart. */
    val interrupted: (String) -> Unit = { key ->
        ledger.current(key)?.id?.let { id -> reply(key, Rpc.error(id, DELIVERY_INTERRUPTED)) }
    }

    private fun prepared(asked: ServerRequestOutstanding, text: String): Boolean = when (asked.response.delivery) {
        null -> stage(asked, text)
        RESPONSE_PREPARED -> {
            if (!asked.response.durable) {
                asked.response.durable = persistence.checkpoint()
                if (!asked.response.durable) retried(asked)
            }
            asked.response.durable
        }
        else -> false
    }

    private fun stage(asked: ServerRequestOutstanding, text: String): Boolean {
        val utf8Bytes = text.encodeToByteArray().size
        val admitted = HolderOutputCapacity.accepts(utf8Bytes) && ledger.reserveResponse(asked, text, utf8Bytes)
        if (!admitted) return false
        asked.response.delivery = RESPONSE_PREPARED
        asked.response.durable = persistence.checkpoint()
        if (!asked.response.durable) retried(asked)
        return asked.response.durable
    }

    fun send(key: String, asked: ServerRequestOutstanding) {
        preparedForSend(asked)?.let { prepared ->
            if (asked.holder.settlementProtocol) {
                sendRecorded(key, asked, prepared)
            } else {
                sendLegacy(key, asked, prepared)
            }
        }
    }

    private fun sendRecorded(key: String, asked: ServerRequestOutstanding, prepared: PreparedResponse) {
        if (!prepared.link.accepts(HolderProtocol.reverse(key))) {
            retried(asked)
        } else if (writing(asked)) {
            if (prepared.link.send(HolderProtocol.reverse(key))) prepared.link.send(prepared.text)
        }
    }

    private fun sendLegacy(key: String, asked: ServerRequestOutstanding, prepared: PreparedResponse) {
        if (writing(asked)) prepared.link.send(prepared.text, HolderWritten { settled(key, asked) })
    }

    private fun preparedForSend(asked: ServerRequestOutstanding): PreparedResponse? {
        if (asked.response.delivery != RESPONSE_PREPARED) return null
        val link = asked.holder.link
        val text = asked.response.text
        var ready = asked.holder.attestedGeneration == asked.generation
        if (ready) ready = link != null
        if (ready) ready = text != null
        if (ready) ready = link?.accepts(text.orEmpty()) == true
        if (ready && !asked.response.durable) asked.response.durable = persistence.checkpoint()
        if (!ready || !asked.response.durable) {
            retried(asked)
            return null
        }
        return PreparedResponse(requireNotNull(link), requireNotNull(text))
    }

    /** A persisted write attempt may have reached the holder only partly, so only exact exit can settle it. */
    private fun writing(asked: ServerRequestOutstanding): Boolean {
        if (asked.response.delivery != RESPONSE_PREPARED) return false
        asked.response.delivery = RESPONSE_WRITE_UNCERTAIN
        if (persistence.checkpoint()) {
            asked.response.retries = 0
            asked.holder.reverseResponseRetries = 0
            return true
        }
        asked.response.delivery = RESPONSE_PREPARED
        retried(asked)
        return false
    }

    private fun retried(asked: ServerRequestOutstanding) {
        asked.response.retries = (asked.response.retries + 1).coerceAtMost(RESPONSE_RETRY_LIMIT)
        asked.holder.reverseResponseRetries = asked.response.retries
        persistence.changed()
        asked.holder.scheduleReverseResponseRetry?.invoke(asked.generation)
    }

    private fun settled(key: String, asked: ServerRequestOutstanding) {
        if (ledger.settles(key, asked, persistence::checkpoint)) {
            asked.holder.reverseResponseRetries = 0
            persistence.changed()
        }
    }
}

private const val RECOVERY_INTERRUPTED = "the front restarted before this server request could reach its client"
private const val DELIVERY_INTERRUPTED = "the front could not durably retain this server request"
private const val RESPONSE_ID = "response_id"
private const val SESSION = "session"
private const val ANSWERING = "answering"
private const val RESPONSE = "response"
private const val RESPONSE_STATUS = "response_status"
private const val RESPONSE_RETRIES = "response_retries"
private const val RESPONSE_RETRY_LIMIT = 3
internal const val MAX_RETAINED_REVERSE = 256
internal const val MAX_RETAINED_RESPONSE_BYTES = 16L * 1024 * 1024
internal const val RESPONSE_PREPARED = "prepared"
internal const val RESPONSE_WRITE_UNCERTAIN = "write_uncertain"
private const val RESPONSE_LEGACY_UNCERTAIN = "legacy_write_uncertain"
