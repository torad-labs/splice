// The holders the front talks to: each connected once, handshaken once, and remembered on disk, so a front restart or
// an in-place upgrade finds every server as it was and never sends a running server a second initialize.
package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One holder's socket, as the front sees it. */
data class Holder(val socket: String) {
    var link: HolderLink? = null
    var identity: String? = null
    var init: JsonObject? = null
    var ready = false
    var connecting = false
    var listed = false

    /** When a session last sent this holder a request, in epoch milliseconds; the daemon stops a shared holder
     *  idle past its bound. */
    var lastCall = 0L
    val waiters = ArrayList<(String?) -> Unit>()

    /** Hands [failure], or null for ready, to everyone waiting on this holder. */
    fun settle(failure: String?) {
        connecting = false
        ready = failure == null
        if (failure != null) link?.close()
        val waiting = waiters.toList()
        waiters.clear()
        waiting.forEach { it(failure) }
    }
}

class Holders(
    private val files: HubFiles,
    private val links: Links,
    private val timers: Timers,
    private val clock: Clock,
    private val routes: Routes,
    private val relay: Relay,
    private val changed: () -> Unit,
) {
    private val known = HashMap<String, Holder>()
    private var next = 0L

    /** Set while the front stops or upgrades: a link that closes then is the front's doing, not a lost holder. */
    var stopping = false

    fun of(route: Route): Holder? = route.socket?.let(known::get)

    /** A holder connection carried across an exec. The holder was initialized before, so it is ready as it stands. */
    fun adopt(fd: Int, socket: String, saved: ByteArray) {
        val holder = known.getOrPut(socket) { Holder(socket) }
        val link = links.adopt(fd, socket, saved, { line -> fromHolder(holder, line) }, { lost(holder) })
        holder.link = link
        holder.ready = holder.init != null
        if (!holder.ready) link.close()
    }

    /** The server's initialize result without starting it: the front's memory, else the kept listing, else a start. */
    fun knownInit(route: Route, then: (JsonObject?, String?) -> Unit) {
        of(route)?.init?.let { return then(it, null) }
        files.listingInit(route.listing)?.let { return then(it, null) }
        live(route) { failure -> then(of(route)?.init, failure) }
    }

    /** [route]'s holder, connected and initialized; started through the daemon when nothing listens. */
    fun live(route: Route, then: (String?) -> Unit) {
        val socket = route.socket ?: return then("splice did not say where ${route.server} runs")
        val holder = known.getOrPut(socket) { Holder(socket) }
        if (holder.ready) return then(null)
        holder.waiters += then
        if (holder.connecting) return
        holder.connecting = true
        attach(route, holder, startAllowed = true)
    }

    /** Marks the holders at [sockets] as called now, so the daemon's idle stop never takes one mid-call. */
    fun touch(sockets: Set<String>) {
        val now = clock.epoch()
        sockets.mapNotNull(known::get).forEach { it.lastCall = now }
    }

    fun restore(saved: JsonObject?) {
        saved?.forEach { (socket, v) ->
            val o = v as? JsonObject ?: return@forEach
            val h = known.getOrPut(socket) { Holder(socket) }
            h.identity = Rpc.str(o, IDENTITY)
            h.init = o[INITIALIZE] as? JsonObject
            h.lastCall = (o[LAST_CALL] as? JsonPrimitive)?.longOrNull ?: 0L
        }
    }

    fun state(): JsonObject =
        JsonObject(
            known.values.mapNotNull { h ->
                h.init?.let { init ->
                    h.socket to buildJsonObject {
                        put(IDENTITY, h.identity)
                        put(INITIALIZE, init)
                        put(LAST_CALL, h.lastCall)
                    }
                }
            }.toMap(),
        )

    private fun attach(route: Route, holder: Holder, startAllowed: Boolean) {
        val link = links.connect(holder.socket, { line -> fromHolder(holder, line) }, { lost(holder) })
        when {
            link != null -> handshake(route, holder, link)
            startAllowed -> routes.start(route) { failure ->
                if (failure == null) attach(route, holder, startAllowed = false) else holder.settle(failure)
            }
            else -> holder.settle("${route.server} did not start")
        }
    }

    private fun handshake(route: Route, holder: Holder, link: HolderLink) {
        holder.link = link
        val identity = Ids.socket(holder.socket)
        if (holder.init != null && holder.identity == identity) {
            files.say("adopted ${route.server} at ${holder.socket} without a handshake")
            return holder.settle(null)
        }
        holder.identity = identity
        holder.init = null
        link.send(Rpc.encode(hello()))
        timers.at(clock.now() + HANDSHAKE_BUDGET) {
            if (!holder.ready && holder.connecting) holder.settle("${route.server} did not answer initialize")
        }
    }

    /** The front's initialize offers roots to every server, as the in-process host does (JsonRpcCodec): a server
     *  that depends on them then asks. A session's own copy is asked its session's; a shared copy's ask is answered
     *  with none and recorded (Relay.sharedRoots), so its next sessions each start their own copy in their own folder.
     *  Offering roots only to per-session copies left a server that checks the capability on the shared copy, in the
     *  hub's home folder, never asking and so never recorded. */
    private fun hello(): JsonObject {
        val capabilities = buildJsonObject { put("roots", JsonObject(emptyMap())) }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put(Rpc.ID, "$HELLO${++next}")
            put(Rpc.METHOD, INITIALIZE)
            put(
                Rpc.PARAMS,
                buildJsonObject {
                    put(Rpc.PROTOCOL, Rpc.VERSIONS.first())
                    put("capabilities", capabilities)
                    put("clientInfo", buildJsonObject { put("name", "splice-mcp-front"); put("version", "1") })
                },
            )
        }
    }

    /** A holder's line: the answer to the front's own initialize is kept here; everything else is the relay's. */
    private fun fromHolder(holder: Holder, line: String) {
        val msg = JsonText.objectOrNull(line) ?: return
        val key = (msg[Rpc.ID] as? JsonPrimitive)?.contentOrNull
        val handshake = Rpc.str(msg, Rpc.METHOD) == null && key?.startsWith(HELLO) == true
        if (handshake) handshaken(holder, msg) else relay.fromHolder(holder, msg)
    }

    private fun handshaken(holder: Holder, msg: JsonObject) {
        val init = msg[Rpc.RESULT] as? JsonObject ?: return holder.settle("the server refused initialize")
        holder.init = init
        holder.lastCall = clock.epoch()
        holder.link?.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        changed()
        holder.settle(null)
    }

    private fun lost(holder: Holder) {
        holder.link = null
        holder.ready = false
        holder.connecting = false
        if (stopping) return
        files.say("lost the holder at ${holder.socket}")
        holder.init = null
        holder.identity = null
        routes.forget(holder.socket)
        relay.lost(holder)
        holder.settle("${holder.socket} closed")
    }
}

private const val HELLO = "i:"
private const val IDENTITY = "identity"
private const val INITIALIZE = "initialize"
private const val LAST_CALL = "last_call"
private const val HANDSHAKE_BUDGET = 60_000L
