// Calls in flight between sessions and holders. Every request a session sends gets a front-wide id, so many sessions
// can share one server, and its answer goes back under the session's own id; a server's own request goes to the
// session whose call is newest.
package splice.hub.front

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A session's request waiting for its server. */
data class Pending(
    val conn: HttpConn,
    val clientId: JsonElement,
    val session: Session,
    val method: String,
    val at: Long,
)

class Relay(private val sessions: Map<String, Session>, private val routes: Routes, private val clock: Clock) {
    private val pending = HashMap<String, Pending>()
    private val fromServer = HashMap<String, Pair<Holder, JsonElement>>()
    private var next = 0L

    /** Calls sent and not yet answered; a stopping front waits for these. */
    fun inFlight(): Int = pending.size

    /** The sockets of holders with a call out: a holder working on a long call is in use, not idle. */
    fun busy(): Set<String> = pending.values.mapNotNull { it.session.route.socket }.toSet()

    /** Sends a session's request to [holder] under a front-wide id. */
    fun call(holder: Holder, conn: HttpConn, session: Session, msg: JsonObject, id: JsonElement) {
        val key = "f:${++next}"
        pending[key] = Pending(conn, id, session, Rpc.str(msg, Rpc.METHOD).orEmpty(), clock.now())
        conn.onGone = { pending.remove(key) }
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
    fun answer(msg: JsonObject, id: JsonElement) {
        val (holder, original) = fromServer.remove((id as? JsonPrimitive)?.contentOrNull ?: return) ?: return
        holder.link?.send(Rpc.with(msg, Rpc.ID, original))
    }

    fun fromHolder(holder: Holder, msg: JsonObject) {
        val id = msg[Rpc.ID]
        val method = Rpc.str(msg, Rpc.METHOD)
        when {
            method == null -> (id as? JsonPrimitive)?.contentOrNull?.let { answered(holder, it, msg) }
            id != null -> asks(holder, msg, id)
            else -> told(holder, msg, method)
        }
    }

    /** Fails, in words, every call in flight to a holder that went away. A session's own copy also says its state
     *  is gone (spec section 8): what that server held for this session alone, open files, a login, a page, died with
     *  it, and the next call starts a fresh copy, not the same one. A shared server keeps the plain line. */
    fun lost(holder: Holder) {
        pending.entries.filter { it.value.session.route.socket == holder.socket }.toList().forEach { (key, call) ->
            pending.remove(key)
            val server = call.session.route.server
            val words = if (call.session.route.shared) {
                "$server stopped during this call; the next call starts it again"
            } else {
                "$server, this session's own copy, stopped during this call and the state it held for this session " +
                    "was lost; the next call starts a fresh copy"
            }
            call.conn.reply(OK, Rpc.error(call.clientId, words))
        }
    }

    private fun ownKey(asked: JsonElement?, session: Session): String? =
        asked?.let { pending.entries.firstOrNull { it.value.clientId == asked && it.value.session === session }?.key }

    private fun answered(holder: Holder, key: String, msg: JsonObject) {
        val call = pending.remove(key) ?: return
        call.conn.onGone = null
        call.conn.reply(OK, Rpc.with(msg, Rpc.ID, call.clientId))
        val tools = msg[Rpc.RESULT]?.takeIf { call.method == Rpc.TOOLS_LIST && !holder.listed } ?: return
        holder.listed = true
        routes.keepListing(call.session.route, holder.init ?: JsonObject(emptyMap()), tools)
    }

    /** A request from the server to a client (roots, sampling): to the session whose call is newest, else any session
     *  of this holder with a stream open. */
    private fun asks(holder: Holder, msg: JsonObject, id: JsonElement) {
        val newest = pending.values.filter { it.session.route.socket == holder.socket && it.session.stream != null }
            .maxByOrNull { it.at }?.session
        val target = newest ?: sessions.values.firstOrNull { it.route.socket == holder.socket && it.stream != null }
        if (target == null) {
            holder.link?.send(Rpc.error(id, "no client has a stream open"))
            return
        }
        val key = "s:${++next}"
        fromServer[key] = holder to id
        target.stream?.message(Rpc.with(msg, Rpc.ID, JsonPrimitive(key)))
    }

    private fun told(holder: Holder, msg: JsonObject, method: String) {
        if (method == "notifications/tools/list_changed") holder.listed = false
        val text = Rpc.encode(msg)
        sessions.values.filter { it.route.socket == holder.socket }.forEach { it.stream?.message(text) }
    }
}

private const val CANCELLED = "notifications/cancelled"
private const val REQUEST_ID = "requestId"
