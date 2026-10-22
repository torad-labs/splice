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
import kotlinx.serialization.json.put

/** A client request admitted to a holder under a front-wide wire id. Its client may disappear before the holder settles. */
data class WireOutstanding(
    val holder: Holder,
    val generation: String,
    var conn: HttpConn?,
    val clientId: JsonElement,
    val session: Session,
    val method: String,
    val at: Long,
)

class Relay(
    private val sessions: Map<String, Session>,
    private val routes: Routes,
    private val clock: Clock,
    private val changed: () -> Unit,
) {
    private val wireOutstanding = HashMap<String, WireOutstanding>()
    private val fromServer = ServerRequests(changed)
    private var next = 0L

    /** Wire ids not yet answered or confirmed lost in either direction. */
    val inFlight: Int get() = wireOutstanding.size + fromServer.size

    fun inFlight(socket: String?): Int =
        wireOutstanding.values.count { it.holder.socket == socket } +
            fromServer.count(socket)

    fun awaitsExit(holder: Holder, generation: String): Boolean =
        wireOutstanding.values.any { it.holder === holder && it.generation == generation } ||
            fromServer.awaitsExit(holder, generation)

    fun attachedClients(server: String, launch: String): Int =
        wireOutstanding.values.count {
            it.conn != null && it.session.route.server == server && it.session.route.launch == launch
        }

    /** The sockets of holders with a call out: a holder working on a long call is in use, not idle. */
    val busy: Set<String>
        get() = wireOutstanding.values.map { it.holder.socket }.toSet() + fromServer.busy

    /** Sends a session's request to [holder] under a front-wide id. The wire id remains until the holder settles it. */
    fun call(holder: Holder, conn: HttpConn, session: Session, msg: JsonObject, id: JsonElement) {
        val generation = holder.generation
            ?: return conn.reply(UNAVAILABLE, Rpc.error(id, "the holder has no owner-confirmed generation"))
        val key = "f:${++next}"
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
        conn.onGone = { if (wireOutstanding[key] === call) call.conn = null }
        holder.lastCall = clock.epoch()
        holder.link?.send(Rpc.with(msg, Rpc.ID, JsonPrimitive(key)))
    }

    /** A session's notification, with a cancellation's request id rewritten to the one the server knows. */
    fun notify(holder: Holder, session: Session, msg: JsonObject) {
        val params = (msg[Rpc.PARAMS] as? JsonObject)?.takeIf { Rpc.str(msg, Rpc.METHOD) == CANCELLED }
        val renumbered = params?.let { p ->
            ownKey(p[REQUEST_ID], session)?.let { key ->
                JsonObject(msg + (Rpc.PARAMS to JsonObject(p + (REQUEST_ID to JsonPrimitive(key)))))
            }
        }
        holder.link?.send(Rpc.encode(renumbered ?: msg))
    }

    /** A session's answer to a server's request, sent back under the id the server used. */
    fun answer(msg: JsonObject, id: JsonElement) = fromServer.answer(msg, id)

    fun fromHolder(holder: Holder, msg: JsonObject) {
        val id = msg[Rpc.ID]
        val method = Rpc.str(msg, Rpc.METHOD)
        when {
            method == null -> (id as? JsonPrimitive)?.contentOrNull?.let { answered(holder, it, msg) }
            id != null -> asks(holder, msg, id)
            else -> told(holder, msg, method)
        }
    }

    /** Losing a socket does not prove that the holder's child stopped executing its operation. */
    fun lost(holder: Holder) {
        wireOutstanding.values.filter { it.holder === holder && it.generation == holder.generation }.forEach { call ->
            call.conn?.reply(
                OK,
                Rpc.error(
                    call.clientId,
                    "the holder connection was lost; the operation's outcome is unknown and it was not replayed",
                ),
            )
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

    /** A shared copy (or one no session is bound to yet) asked for roots: no session's folder is the right answer, so
     *  the front answers none, as the in-process host does, and tells the daemon, which records it so the next
     *  connection starts its own copy in its own folder (spec step 3). False for a session's own copy, whose one
     *  session is asked. */
    private fun sharedRoots(holder: Holder, id: JsonElement, key: String): Boolean {
        val route = sessions.values.firstOrNull { it.route.socket == holder.socket }?.route
        if (route != null && !route.shared) return false
        fromServer.reply(key, Rpc.result(id, buildJsonObject { put("roots", JsonArray(emptyList())) }))
        route?.let(routes::rootsAsked)
        return true
    }

    private fun ownKey(asked: JsonElement?, session: Session): String? =
        asked?.let {
            wireOutstanding.entries.firstOrNull { entry ->
                entry.value.clientId == asked && entry.value.session === session
            }?.key
        }

    private fun answered(holder: Holder, key: String, msg: JsonObject) {
        val call = wireOutstanding[key]?.takeIf { it.holder === holder && it.generation == holder.generation } ?: return
        wireOutstanding.remove(key)
        call.conn?.let { conn ->
            conn.onGone = null
            conn.reply(OK, Rpc.with(msg, Rpc.ID, call.clientId))
        }
        changed()
        val tools = msg[Rpc.RESULT]?.takeIf { call.method == Rpc.TOOLS_LIST && !holder.listed } ?: return
        holder.listed = true
        routes.keepListing(call.session.route, holder.init ?: JsonObject(emptyMap()), tools)
    }

    /** A request from the server to a client (roots, sampling): to the session whose call is newest, else any session
     *  of this holder with a stream open. */
    private fun asks(holder: Holder, msg: JsonObject, id: JsonElement) {
        val key = fromServer.retain(holder, id)
        if (Rpc.str(msg, Rpc.METHOD) == ROOTS_LIST && sharedRoots(holder, id, key)) return
        val newest = wireOutstanding.values
            .filter { it.session.route.socket == holder.socket && it.session.stream != null }
            .maxByOrNull { it.at }
            ?.session
        val target = newest ?: sessions.values.firstOrNull { it.route.socket == holder.socket && it.stream != null }
        if (target == null) {
            fromServer.reply(key, Rpc.error(id, "no client has a stream open"))
            return
        }
        target.stream?.message(Rpc.with(msg, Rpc.ID, JsonPrimitive(key)))
    }

    private fun told(holder: Holder, msg: JsonObject, method: String) {
        // Progress and other notifications do not settle a wire id; only its response or this holder's confirmed exit does.
        if (method == "notifications/tools/list_changed") holder.listed = false
        val text = Rpc.encode(msg)
        sessions.values.filter { it.route.socket == holder.socket }.forEach { it.stream?.message(text) }
    }
}

private const val CANCELLED = "notifications/cancelled"
private const val REQUEST_ID = "requestId"
private const val ROOTS_LIST = "roots/list"
