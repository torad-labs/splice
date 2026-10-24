// Calls in flight between sessions and holders. Every request a session sends gets a front-wide id, so many sessions
// can share one server, and its answer goes back under the session's own id; a server's own request goes to the
// session whose call is newest. A shared server's roots request is the one exception: it is answered with no roots
// and reported to the daemon, never sent to one session, whose folder every other session on the copy would get.
package splice.hub.front

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.hub.holder.HolderAdmissionWire
import splice.hub.holder.HolderProtocol

/** A client request admitted to a holder under a front-wide wire id. Its client may disappear before the holder settles. */
data class WireOutstanding(
    val holder: Holder,
    val generation: String,
    var conn: HttpConn?,
    val clientId: JsonElement?,
    val session: Session?,
    val method: String,
    val at: Long,
) {
    /** Socket-write proof is not proof that a live front has consumed its response data. */
    var backendSettled = false
    var recorded = false
}

private class ForwardAdmission(
    val generation: String?,
    val link: HolderLink?,
    text: String,
    retiring: Boolean,
    recovering: Boolean,
    retained: Int,
) {
    val refusal: String? = when {
        generation == null -> "the holder has no owner-confirmed generation"
        retiring -> "the holder is retiring"
        link == null -> "the holder is no longer connected"
        recovering || link.negotiating -> "the holder is reconciling durable delivery"
        retained >= MAX_RETAINED_FORWARD -> "the front retained-work limit is full"
        !link.accepts(text) -> "the holder output queue is full"
        else -> null
    }
}

class Relay(
    private val sessions: Map<String, Session>,
    private val routes: Routes,
    private val clock: Clock,
    private val changed: () -> Unit,
    private val checkpoint: () -> Boolean,
) {
    private val wireOutstanding = HashMap<String, WireOutstanding>()
    private val keys = RelayWireKeys()
    private val fromServer = ServerRequests(changed, checkpoint, keys)
    private val settlements = RelaySettlements(
        wireOutstanding,
        fromServer,
        HolderPersistence(changed, checkpoint),
    )
    private val resume = RelayResumeBinding(settlements)
    internal val notifications = RelayNotifications(wireOutstanding)
    internal val inbound = RelayInbound(
        sessions,
        routes,
        wireOutstanding,
        fromServer,
        settlements,
        resume,
    )

    /** Wire ids not yet answered or confirmed lost in either direction. */
    val inFlight: Int get() = wireOutstanding.size + fromServer.size

    fun inFlight(socket: String?): Int =
        wireOutstanding.values.count { it.holder.socket == socket } +
            fromServer.count(socket)

    fun awaitsExit(holder: Holder, generation: String): Boolean =
        wireOutstanding.values.any { it.holder === holder && it.generation == generation && !it.backendSettled } ||
            fromServer.awaitsExit(holder, generation)

    /** Rechecks every restored ledger against the daemon's generation-addressed exit evidence. */
    fun reconcileExits(confirm: (Holder, String) -> Unit) {
        (wireOutstanding.values.map { it.holder to it.generation } + fromServer.retainedGenerations)
            .distinct()
            .forEach { (holder, generation) -> confirm(holder, generation) }
    }

    fun restore(saved: JsonObject?, holder: (String) -> Holder) {
        val counters = saved?.get(WIRE_KEYS)
        require(counters == null || counters is JsonObject) { "the persisted wire-key counters are malformed" }
        keys.restore(counters as? JsonObject)
        val retained = saved?.get(RELAY_CALLS) as? JsonObject
        require((retained?.size ?: 0) <= MAX_RETAINED_FORWARD) { "the persisted forward ledger exceeds its item limit" }
        retained?.forEach { (key, value) -> restoreCall(key, value, holder) }
        fromServer.restore(saved?.get(SERVER_REQUESTS) as? JsonObject, holder, sessions)
        fromServer.retainedGenerations.map { it.first }.distinct().forEach(resume::remember)
    }

    fun state(): JsonObject = buildJsonObject {
        put(WIRE_KEYS, keys.state())
        put(
            RELAY_CALLS,
            JsonObject(
                wireOutstanding.mapValues { (_, call) ->
                    buildJsonObject {
                        put(SOCKET, call.holder.socket)
                        put(GENERATION, call.generation)
                        call.clientId?.let { put(CLIENT_ID, it) }
                        call.session?.let { put(CALL_SESSION, it.id) }
                        put(CALL_METHOD, call.method)
                        put(CALL_AT, call.at)
                        put(CALL_RECORDED, call.recorded)
                    }
                },
            ),
        )
        put(SERVER_REQUESTS, fromServer.state())
    }

    fun attachedClients(server: String, launch: String): Int =
        wireOutstanding.values.count { call ->
            call.conn != null && call.session?.route?.let { route ->
                route.server == server && route.launch == launch
            } == true
        }

    /** The sockets of holders with a call out: a holder working on a long call is in use, not idle. */
    val busy: Set<String>
        get() = wireOutstanding.values.map { it.holder.socket }.toSet() + fromServer.busy

    /** Sends a session's request to [holder] under a front-wide id. The wire id is durable before the holder sees it. */
    fun call(holder: Holder, conn: HttpConn, session: Session, msg: JsonObject, id: JsonElement) {
        if (reserved(session, id)) {
            conn.reply(UNAVAILABLE, Rpc.error(id, "request id is already in flight for this session"))
            return
        }
        val key = keys.forward()
        if (key == null) {
            conn.reply(UNAVAILABLE, Rpc.error(id, "the durable wire-key sequence is exhausted"))
            return
        }
        val text = Rpc.with(msg, Rpc.ID, JsonPrimitive(key))
        val forward = ForwardAdmission(
            holder.generation,
            holder.link,
            text,
            holder.retirementFence == holder.generation,
            holder.settlementRecovery != null,
            wireOutstanding.size,
        )
        val refusal = forward.refusal
        if (refusal != null) {
            conn.reply(UNAVAILABLE, Rpc.error(id, refusal))
            return
        }
        val call = WireOutstanding(
            holder,
            requireNotNull(forward.generation),
            conn,
            id,
            session,
            Rpc.str(msg, Rpc.METHOD).orEmpty(),
            clock.now(),
        )
        call.recorded = forward.link?.recorded == true
        wireOutstanding[key] = call
        resume.remember(holder)
        if (checkpoint()) {
            conn.onGone = { settlements.detached(key, call) }
            holder.lastCall = clock.epoch()
            requireNotNull(forward.link).send(text)
        } else {
            wireOutstanding.remove(key)
            conn.reply(UNAVAILABLE, Rpc.error(id, "the front could not durably retain this request"))
        }
    }

    /** A session's answer to a server's request, sent back under the id the server used. */
    fun answer(session: Session, msg: JsonObject, id: JsonElement): Boolean? = fromServer.answer(session, msg, id)

    /** A retired session receives no more work, so every recoverable reverse request gets a final explicit error. */
    fun retire(session: Session, reason: String) = fromServer.retire(session, reason)

    /** Losing a socket does not prove that the holder's child stopped executing its operation. */
    fun lost(holder: Holder) {
        wireOutstanding
            .filterValues { it.holder === holder && it.generation == holder.generation }
            .toList()
            .forEach { (key, call) ->
                val conn = call.conn
                settlements.detached(key, call)
                call.clientId?.let { id ->
                    conn?.reply(
                        OK,
                        Rpc.error(
                            id,
                            "the holder connection was lost; " +
                                "the operation's outcome is unknown and it was not replayed",
                        ),
                    )
                }
            }
        // Keep both directions charged. A later response or the owner's exact-generation exit can settle them; link loss cannot.
        changed()
    }

    /** Removes only ids charged to [generation], after the daemon owner confirmed that precise holder generation exited. */
    fun exited(holder: Holder, generation: String) {
        wireOutstanding.entries
            .filter { (_, call) -> call.holder === holder && call.generation == generation }
            .forEach { (key, call) ->
                call.backendSettled = true
                if (call.conn == null) wireOutstanding.remove(key)
            }
        fromServer.exited(holder, generation)
        settlements.exited(holder, generation)
        changed()
    }

    private fun restoreCall(key: String, value: JsonElement, holder: (String) -> Holder) {
        val body = value as? JsonObject ?: return
        val socket = Rpc.str(body, SOCKET) ?: return
        val generation = Rpc.str(body, GENERATION) ?: return
        wireOutstanding[key] = WireOutstanding(
            holder(socket),
            generation,
            null,
            body[CLIENT_ID],
            Rpc.str(body, CALL_SESSION)?.let(sessions::get),
            Rpc.str(body, CALL_METHOD).orEmpty(),
            (body[CALL_AT] as? JsonPrimitive)?.longOrNull ?: 0L,
        ).also {
            it.recorded = Rpc.str(body, CALL_RECORDED) == "true"
            resume.remember(it.holder)
        }
        keys.retained(key)
    }

    private fun reserved(session: Session, id: JsonElement): Boolean =
        wireOutstanding.values.any { it.session === session && it.clientId == id }
}

/** The notification path owns only request-id translation; it never changes a generation ledger. */
internal class RelayNotifications(private val calls: Map<String, WireOutstanding>) {
    /** A notification is accepted only once its holder link accepted its complete wire message. */
    fun notify(holder: Holder, session: Session, msg: JsonObject): Boolean {
        val cancelled = Rpc.str(msg, Rpc.METHOD) == CANCELLED
        val params = msg[Rpc.PARAMS] as? JsonObject
        val key = params?.takeIf { cancelled }?.get(REQUEST_ID)?.let { ownKey(holder, it, session) }
        if (cancelled && key == null) return true
        val text = key?.let { Rpc.encode(withRequestId(msg, requireNotNull(params), it)) } ?: Rpc.encode(msg)
        return holder.link?.send(text) == true
    }

    private fun ownKey(holder: Holder, id: JsonElement, session: Session): String? =
        calls.entries.firstOrNull { (_, call) ->
            listOf(
                call.clientId == id,
                call.session === session,
                call.holder === holder,
                call.generation == holder.generation,
            ).all { it }
        }?.key

    private fun withRequestId(msg: JsonObject, params: JsonObject, key: String): JsonObject =
        JsonObject(msg + (Rpc.PARAMS to JsonObject(params + (REQUEST_ID to JsonPrimitive(key)))))
}

/** Routes holder traffic while the relay owns the durable ledger and the server-request ledger owns flush settlement. */
internal class RelayInbound(
    private val sessions: Map<String, Session>,
    private val routes: Routes,
    private val calls: MutableMap<String, WireOutstanding>,
    private val requests: ServerRequests,
    private val settlements: RelaySettlements,
    private val resume: RelayResumeBinding,
) {
    /** The link recovery owner calls this before JSON-RPC parsing, including while it still proves initialization. */
    fun control(holder: Holder, line: String): Boolean = HolderProtocol.event(line)?.let { frame ->
        settlements.control(holder, frame)
        true
    } == true

    fun fromHolder(holder: Holder, msg: JsonObject) {
        val id = msg[Rpc.ID]
        val method = Rpc.str(msg, Rpc.METHOD)
        when {
            method == null -> (id as? JsonPrimitive)?.contentOrNull?.let { answered(holder, it, msg) }
            id != null -> asks(holder, msg, id)
            else -> told(holder, msg, method)
        }
    }

    /** A request from the server goes to the newest live client, or receives an explicit no-client error. */
    private fun asks(holder: Holder, msg: JsonObject, id: JsonElement) {
        if (holder.retirementFence == holder.generation) {
            refuse(holder, id, "the holder is retiring")
        } else {
            when (val retained = requests.retain(holder, id)) {
                is ServerRequestRetention.Retained -> {
                    resume.remember(holder)
                    deliver(holder, msg, id, retained.key)
                }
                ServerRequestRetention.CapacityRefused ->
                    refuse(holder, id, "the front retained reverse capacity is full")
                ServerRequestRetention.PersistenceFailed -> Unit
            }
        }
    }

    private fun refuse(holder: Holder, id: JsonElement, reason: String) {
        val text = Rpc.error(id, reason)
        holder.link?.let { link -> link.send(if (link.recorded) HolderAdmissionWire.reject(text) else text) }
    }

    private fun deliver(holder: Holder, msg: JsonObject, id: JsonElement, key: String) {
        if (!sharedRoots(holder, msg, id, key)) {
            val target = target(holder)
            if (target == null) {
                requests.reply(key, Rpc.error(id, "no client has a stream open"))
            } else if (requests.bind(key, target)) {
                val response = Rpc.with(msg, Rpc.ID, JsonPrimitive(key))
                val published = target.stream?.message(response) == true
                if (!published) {
                    requests.reply(key, Rpc.error(id, "the selected client stream could not accept the request"))
                }
            }
        }
    }

    private fun target(holder: Holder): Session? =
        newest(holder) ?: sessions.values.firstOrNull { it.route.socket == holder.socket && it.stream != null }

    private fun newest(holder: Holder): Session? =
        calls.values
            .filter { call ->
                call.session?.let { session ->
                    sessions[session.id] === session && session.route.socket == holder.socket && session.stream != null
                } == true
            }
            .maxByOrNull { it.at }
            ?.session

    /** A shared copy has no single correct root, so it gets the standard empty result and records the condition. */
    private fun sharedRoots(holder: Holder, msg: JsonObject, id: JsonElement, key: String): Boolean {
        if (Rpc.str(msg, Rpc.METHOD) != ROOTS_LIST) return false
        val route = sessions.values.firstOrNull { it.route.socket == holder.socket }?.route
        if (route != null && !route.shared) return false
        requests.reply(key, Rpc.result(id, buildJsonObject { put("roots", JsonArray(emptyList())) }))
        route?.let(routes::rootsAsked)
        return true
    }

    private fun answered(holder: Holder, key: String, msg: JsonObject) {
        val call = settlements.answered(holder, key) ?: return
        deliver(call, msg)
        listed(holder, call, msg)
    }

    private fun deliver(call: WireOutstanding, msg: JsonObject) {
        val conn = call.conn
        call.conn = null
        conn?.let {
            conn.onGone = null
            call.clientId?.let { id -> conn.reply(OK, Rpc.with(msg, Rpc.ID, id)) }
        }
    }

    private fun listed(holder: Holder, call: WireOutstanding, msg: JsonObject) {
        val tools = msg[Rpc.RESULT]?.takeIf { call.method == Rpc.TOOLS_LIST && !holder.listed } ?: return
        val route = call.session?.route ?: return
        holder.listed = true
        routes.keepListing(route, holder.init ?: JsonObject(emptyMap()), tools)
    }

    private fun told(holder: Holder, msg: JsonObject, method: String) {
        if (method == "notifications/tools/list_changed") holder.listed = false
        if (method == CANCELLED) {
            cancelled(holder, msg)
        } else {
            val text = Rpc.encode(msg)
            sessions.values.filter { it.route.socket == holder.socket }.forEach { it.stream?.message(text) }
        }
    }

    private fun cancelled(holder: Holder, msg: JsonObject) {
        val params = msg[Rpc.PARAMS] as? JsonObject ?: return
        val forwarded = params[REQUEST_ID]?.let { requests.forwarded(holder, it) } ?: return
        val session = forwarded.second
        if (sessions[session.id] === session) {
            val rewritten = JsonObject(
                msg + (Rpc.PARAMS to JsonObject(params + (REQUEST_ID to JsonPrimitive(forwarded.first)))),
            )
            session.stream?.message(Rpc.encode(rewritten))
        }
    }
}

private const val WIRE_KEYS = "wire_keys"
private const val RELAY_CALLS = "calls"
private const val SERVER_REQUESTS = "server_requests"
private const val CLIENT_ID = "client_id"
private const val CALL_SESSION = "session"
private const val CALL_METHOD = "method"
private const val CALL_AT = "at"
private const val CALL_RECORDED = "recorded"
internal const val CANCELLED = "notifications/cancelled"
private const val REQUEST_ID = "requestId"
private const val ROOTS_LIST = "roots/list"
private const val MAX_RETAINED_FORWARD = 256
