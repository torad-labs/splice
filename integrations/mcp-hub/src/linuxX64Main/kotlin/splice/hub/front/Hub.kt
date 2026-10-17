// The hub front's sessions: each session's HTTP side, from initialize to its stream. It answers initialize from what
// it already knows, hands calls to the relay and asks the daemon, through the routes, only where a server is and to
// start one. All of it is kept in front.json, whose shape the daemon's sweep reads: "routes" by route key, "holders"
// by socket, and "sessions" as session id to route key.
package splice.hub.front

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

data class Session(val id: String, val route: Route) {
    var stream: HttpConn? = null
}

class Hub(
    private val files: HubFiles,
    daemon: Daemon,
    links: Links,
    private val timers: Timers,
    private val clock: Clock,
) {
    private val sessions = HashMap<String, Session>()
    private val routes = Routes(daemon, timers, clock, ::save)
    private val relay = Relay(sessions, routes, clock)
    private val holders = Holders(files, links, timers, clock, routes, relay, ::save)

    /** Set while the front stops or upgrades, so the links it closes are not taken for lost holders. */
    var stopping: Boolean
        get() = holders.stopping
        set(value) {
            holders.stopping = value
        }

    fun inFlight(): Int = relay.inFlight()

    /** A stream carried across an exec, back on its session. */
    fun adoptStream(conn: HttpConn) {
        val session = conn.streamOf?.let(sessions::get) ?: return conn.close()
        session.stream = conn
        conn.onGone = { if (session.stream === conn) session.stream = null }
    }

    /** A holder connection carried across an exec. */
    fun adoptLink(fd: Int, socket: String, saved: ByteArray) = holders.adopt(fd, socket, saved)

    fun restore() {
        val saved = files.loadState() ?: return
        routes.restore(saved["routes"] as? JsonObject)
        holders.restore(saved["holders"] as? JsonObject)
        (saved["sessions"] as? JsonObject)?.forEach { (id, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let(routes::byKey)?.let { sessions[id] = Session(id, it) }
        }
        files.say("restored ${sessions.size} sessions")
    }

    fun save() {
        val state = buildJsonObject {
            put("routes", routes.state())
            put("holders", holders.state())
            put("sessions", JsonObject(sessions.mapValues { (_, s) -> JsonPrimitive(s.route.key()) }))
        }
        files.saveState(Rpc.encode(state))
    }

    /** Keeps every open stream alive and front.json current for the daemon's sweep: last calls for its idle stop, and
     *  no session whose Claude Code has exited (a launch is that process's pid; an unknown launch is kept). */
    fun keepAlive() {
        sessions.values.filter { it.route.launch.toIntOrNull()?.let(Ids::running) == false }.forEach {
            sessions.remove(it.id)
            it.stream?.close()
        }
        sessions.values.forEach { it.stream?.push(": keepalive\n\n") }
        holders.touch(relay.busy())
        save()
        timers.at(clock.now() + KEEPALIVE) { keepAlive() }
    }

    fun handle(conn: HttpConn, req: Request) {
        val server = req.path.removePrefix(PREFIX).takeIf { req.path.startsWith(PREFIX) && it.isNotEmpty() }
            ?: return conn.reply(NOT_FOUND, Rpc.error(JsonNull, "no such path"))
        val access = files.accessKey() ?: return conn.reply(UNAUTHORIZED)
        if (req.headers["authorization"] != "Bearer $access") return conn.reply(UNAUTHORIZED)
        val session = req.headers["mcp-session-id"]?.let(sessions::get)
        when (req.method) {
            "POST" -> post(conn, req, server, session)
            "GET" -> open(conn, session)
            "DELETE" -> end(conn, session)
            else -> conn.reply(NOT_ALLOWED)
        }
    }

    private fun post(conn: HttpConn, req: Request, server: String, session: Session?) {
        val msg = JsonText.objectOrNull(req.body)
            ?: return conn.reply(BAD_REQUEST, Rpc.error(JsonNull, "the body must be one JSON-RPC object"))
        if (Rpc.str(msg, Rpc.METHOD) == "initialize") {
            return initialize(conn, msg, server, req.headers["x-splice-launch"].orEmpty())
        }
        val bound = session?.takeIf { it.route.server == server }
            ?: return conn.reply(NOT_FOUND, Rpc.error(msg[Rpc.ID] ?: JsonNull, "no such session; initialize again"))
        dispatch(conn, bound, msg)
    }

    private fun dispatch(conn: HttpConn, session: Session, msg: JsonObject) {
        val method = Rpc.str(msg, Rpc.METHOD)
        val id = msg[Rpc.ID]
        when {
            method != null && id != null -> request(conn, session, msg, method, id)
            method != null -> notification(conn, session, msg, method)
            id != null -> conn.reply(ACCEPTED).also { relay.answer(msg, id) }
            else -> conn.reply(BAD_REQUEST, Rpc.error(JsonNull, "neither a request, a notification nor an answer"))
        }
    }

    private fun initialize(conn: HttpConn, msg: JsonObject, server: String, launch: String) {
        val id = msg[Rpc.ID] ?: JsonNull
        val asked = Rpc.str(msg[Rpc.PARAMS] as? JsonObject, Rpc.PROTOCOL)
        val route = routes.of(server, launch)
        routes.resolve(route) { failure ->
            if (failure != null) return@resolve conn.reply(OK, Rpc.error(id, failure))
            holders.knownInit(route) { init, why ->
                if (init == null) return@knownInit conn.reply(OK, Rpc.error(id, why ?: "the server did not start"))
                val session = Session(Ids.session(), route)
                sessions[session.id] = session
                save()
                val version = asked?.takeIf { it in Rpc.VERSIONS } ?: Rpc.str(init, Rpc.PROTOCOL)
                val result = JsonObject(init + (Rpc.PROTOCOL to JsonPrimitive(version)))
                files.say("session ${session.id.take(SHORT)} for ${route.server} (launch '${route.launch}')")
                conn.reply(OK, Rpc.result(id, result), mapOf("Mcp-Session-Id" to session.id))
            }
        }
    }

    private fun request(conn: HttpConn, session: Session, msg: JsonObject, method: String, id: JsonElement) {
        val route = session.route
        if (method == "ping") return conn.reply(OK, Rpc.result(id, JsonObject(emptyMap())))
        val cold = method == Rpc.TOOLS_LIST && holders.of(route)?.ready != true
        val kept = if (cold) files.listing(route.listing) else null
        if (kept != null) return conn.reply(OK, Rpc.result(id, kept))
        // resolved again only after its holder was lost (Routes.forget), so a launch follows a changed plan
        routes.resolve(route) { unresolved ->
            if (unresolved != null) return@resolve conn.reply(OK, Rpc.error(id, unresolved))
            holders.live(route) { failure ->
                val holder = holders.of(route)
                if (failure != null || holder == null) return@live conn.reply(OK, Rpc.error(id, failure ?: "no holder"))
                relay.call(holder, conn, session, msg, id)
            }
        }
    }

    private fun notification(conn: HttpConn, session: Session, msg: JsonObject, method: String) {
        conn.reply(ACCEPTED)
        if (method == "notifications/initialized") return
        val holder = holders.of(session.route)?.takeIf { it.ready } ?: return
        relay.notify(holder, session, msg)
    }

    private fun open(conn: HttpConn, session: Session?) {
        if (session == null) return conn.reply(NOT_FOUND, Rpc.error(JsonNull, "no such session"))
        session.stream?.takeIf { it !== conn }?.close()
        session.stream = conn
        conn.streamOf = session.id
        conn.onGone = { if (session.stream === conn) session.stream = null }
        conn.stream()
    }

    private fun end(conn: HttpConn, session: Session?) {
        session?.let {
            sessions.remove(it.id)
            save()
        }
        conn.reply(OK)
    }
}

private const val PREFIX = "/mcp/"
private const val SHORT = 8
private const val KEEPALIVE = 25_000L
