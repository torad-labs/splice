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

/** A client request admitted to a holder under a front-wide wire id. Its client may disappear before the holder settles. */
data class WireOutstanding(
    val holder: Holder,
    val generation: String,
    var conn: HttpConn?,
    val clientId: JsonElement?,
    val session: Session?,
    val method: String,
    val at: Long,
)

class Relay(
    private val sessions: Map<String, Session>,
    private val routes: Routes,
    private val clock: Clock,
    private val changed: () -> Unit,
    private val checkpoint: () -> Boolean,
) {
    private val wireOutstanding = HashMap<String, WireOutstanding>()
    private val fromServer = ServerRequests(changed, checkpoint)
    internal val notifications = RelayNotifications(wireOutstanding)
    internal val inbound = RelayInbound(sessions, routes, wireOutstanding, fromServer, changed)
    private var next = 0L

    /** Wire ids not yet answered or confirmed lost in either direction. */
    val inFlight: Int get() = wireOutstanding.size + fromServer.size

    fun inFlight(socket: String?): Int =
        wireOutstanding.values.count { it.holder.socket == socket } +
            fromServer.count(socket)

    fun awaitsExit(holder: Holder, generation: String): Boolean =
        wireOutstanding.values.any { it.holder === holder && it.generation == generation } ||
            fromServer.awaitsExit(holder, generation)

    /** Rechecks every restored ledger against the daemon's generation-addressed exit evidence. */
    fun reconcileExits(confirm: (Holder, String) -> Unit) {
        (wireOutstanding.values.map { it.holder to it.generation } + fromServer.retainedGenerations)
            .distinct()
            .forEach { (holder, generation) -> confirm(holder, generation) }
    }

    fun restore(saved: JsonObject?, holder: (String) -> Holder) {
        (saved?.get(RELAY_CALLS) as? JsonObject)?.forEach { (key, value) ->
            val body = value as? JsonObject ?: return@forEach
            val socket = Rpc.str(body, SOCKET) ?: return@forEach
            val generation = Rpc.str(body, GENERATION) ?: return@forEach
            wireOutstanding[key] = WireOutstanding(
                holder(socket),
                generation,
                null,
                body[CLIENT_ID],
                Rpc.str(body, CALL_SESSION)?.let(sessions::get),
                Rpc.str(body, CALL_METHOD).orEmpty(),
                (body[CALL_AT] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
            restored(key)
        }
        fromServer.restore(saved?.get(SERVER_REQUESTS) as? JsonObject, holder, sessions)
    }

    fun state(): JsonObject = buildJsonObject {
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
        val generation = holder.generation
            ?: return conn.reply(UNAVAILABLE, Rpc.error(id, "the holder has no owner-confirmed generation"))
        val link = holder.link
            ?: return conn.reply(UNAVAILABLE, Rpc.error(id, "the holder is no longer connected"))
        val key = nextKey()
        val call = WireOutstanding(
            holder,
            generation,
            conn,
            id,
            session,
            Rpc.str(msg, Rpc.METHOD).orEmpty(),
            clock.now(),
        )
        wireOutstanding[key] = call
        if (!checkpoint()) {
            wireOutstanding.remove(key)
            return conn.reply(UNAVAILABLE, Rpc.error(id, "the front could not durably retain this request"))
        }
        conn.onGone = { if (wireOutstanding[key] === call) call.conn = null }
        holder.lastCall = clock.epoch()
        link.send(Rpc.with(msg, Rpc.ID, JsonPrimitive(key)))
    }

    /** A session's answer to a server's request, sent back under the id the server used. */
    fun answer(session: Session, msg: JsonObject, id: JsonElement) = fromServer.answer(session, msg, id)

    /** A retired session receives no more work, so every recoverable reverse request gets a final explicit error. */
    fun retire(session: Session, reason: String) = fromServer.retire(session, reason)

    /** Losing a socket does not prove that the holder's child stopped executing its operation. */
    fun lost(holder: Holder) {
        wireOutstanding.values.filter { it.holder === holder && it.generation == holder.generation }.forEach { call ->
            call.clientId?.let { id ->
                call.conn?.reply(
                    OK,
                    Rpc.error(
                        id,
                        "the holder connection was lost; the operation's outcome is unknown and it was not replayed",
                    ),
                )
            }
            call.conn = null
        }
        // Keep both directions charged. A later response or the owner's exact-generation exit can settle them; link loss cannot.
        changed()
    }

    /** Removes only ids charged to [generation], after the daemon owner confirmed that precise holder generation exited. */
    fun exited(holder: Holder, generation: String) {
        wireOutstanding.entries
            .filter { (_, call) -> call.holder === holder && call.generation == generation }
            .forEach { (key, _) -> wireOutstanding.remove(key) }
        fromServer.exited(holder, generation)
        changed()
    }

    private fun nextKey(): String {
        var key: String
        do {
            key = "f:${++next}"
        } while (key in wireOutstanding)
        return key
    }

    private fun restored(key: String) {
        key.removePrefix("f:").toLongOrNull()?.let { next = maxOf(next, it) }
    }
}

/** The notification path owns only request-id translation; it never changes a generation ledger. */
internal class RelayNotifications(private val calls: Map<String, WireOutstanding>) {
    fun notify(holder: Holder, session: Session, msg: JsonObject) {
        val cancelled = Rpc.str(msg, Rpc.METHOD) == CANCELLED
        val params = msg[Rpc.PARAMS] as? JsonObject
        val key = params?.takeIf { cancelled }?.get(REQUEST_ID)?.let { ownKey(it, session) }
        val text = key?.let { Rpc.encode(withRequestId(msg, requireNotNull(params), it)) } ?: Rpc.encode(msg)
        holder.link?.send(text)
    }

    private fun ownKey(id: JsonElement, session: Session): String? =
        calls.entries.firstOrNull { (_, call) -> call.clientId == id && call.session === session }?.key

    private fun withRequestId(msg: JsonObject, params: JsonObject, key: String): JsonObject =
        JsonObject(msg + (Rpc.PARAMS to JsonObject(params + (REQUEST_ID to JsonPrimitive(key)))))
}

/** Routes holder traffic while the relay owns the durable ledger and the server-request ledger owns flush settlement. */
internal class RelayInbound(
    private val sessions: Map<String, Session>,
    private val routes: Routes,
    private val calls: MutableMap<String, WireOutstanding>,
    private val requests: ServerRequests,
    private val changed: () -> Unit,
) {
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
        val key = requests.retain(holder, id) ?: return
        if (sharedRoots(holder, msg, id, key)) return
        val target = target(holder)
        if (target == null || !requests.bind(key, target)) {
            if (target == null) requests.reply(key, Rpc.error(id, "no client has a stream open"))
            return
        }
        target.stream?.message(Rpc.with(msg, Rpc.ID, JsonPrimitive(key)))
    }

    private fun target(holder: Holder): Session? =
        newest(holder) ?: sessions.values.firstOrNull { it.route.socket == holder.socket && it.stream != null }

    private fun newest(holder: Holder): Session? =
        calls.values
            .filter { call ->
                call.session?.let { session -> session.route.socket == holder.socket && session.stream != null } == true
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
        val call = calls[key]?.takeIf { it.holder === holder && it.generation == holder.generation } ?: return
        calls.remove(key)
        call.conn?.let { conn ->
            conn.onGone = null
            call.clientId?.let { id -> conn.reply(OK, Rpc.with(msg, Rpc.ID, id)) }
        }
        changed()
        val tools = msg[Rpc.RESULT]?.takeIf { call.method == Rpc.TOOLS_LIST && !holder.listed } ?: return
        val route = call.session?.route ?: return
        holder.listed = true
        routes.keepListing(route, holder.init ?: JsonObject(emptyMap()), tools)
    }

    private fun told(holder: Holder, msg: JsonObject, method: String) {
        if (method == "notifications/tools/list_changed") holder.listed = false
        val text = Rpc.encode(msg)
        sessions.values.filter { it.route.socket == holder.socket }.forEach { it.stream?.message(text) }
    }
}

private const val RELAY_CALLS = "calls"
private const val SERVER_REQUESTS = "server_requests"
private const val CLIENT_ID = "client_id"
private const val CALL_SESSION = "session"
private const val CALL_METHOD = "method"
private const val CALL_AT = "at"
private const val CANCELLED = "notifications/cancelled"
private const val REQUEST_ID = "requestId"
private const val ROOTS_LIST = "roots/list"
