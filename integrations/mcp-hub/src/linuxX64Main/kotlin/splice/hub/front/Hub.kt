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
import kotlinx.serialization.json.put

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
    internal val sessions = HashMap<String, Session>()
    internal val routes = Routes(daemon, timers, clock, ::changed)
    internal val relay = Relay(sessions, routes, clock, ::changed)
    internal val holders = Holders(files, links, timers, clock, routes, relay, ::changed)
    internal val replacements = HubReplacements(routes, holders, relay, timers, clock)
    internal val http = HubHttp(files, sessions, routes, holders, relay, ::save)

    /** Set while the front stops or upgrades, so the links it closes are not taken for lost holders. */
    var stopping: Boolean
        get() = holders.stopping
        set(value) {
            holders.stopping = value
        }

    fun inFlight(): Int = relay.inFlight

    /** A stream carried across an exec, back on its session. */
    fun adoptStream(conn: HttpConn) {
        val session = conn.streamOf?.let(sessions::get) ?: return conn.close()
        session.stream = conn
        conn.onGone = { if (session.stream === conn) session.stream = null }
    }

    /** A holder connection carried across an exec. */
    fun adoptLink(fd: Int, socket: String, saved: ByteArray) = holders.links.adopt(fd, socket, saved)

    fun restore() {
        val saved = files.loadState() ?: return
        routes.restore(saved["routes"] as? JsonObject)
        routes.refreshGenerations()
        holders.links.restore(saved["holders"] as? JsonObject)
        holders.replacements.candidates.restore(saved["drains"] as? JsonObject)
        holders.replacements.all().forEach(holders.replacements.links::seed)
        (saved["sessions"] as? JsonObject)?.forEach { (id, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let(routes::byKey)?.let { sessions[id] = Session(id, it) }
        }
        files.say("restored ${sessions.size} sessions")
    }

    fun save() {
        val state = buildJsonObject {
            put("routes", routes.state())
            put("holders", holders.links.state())
            put("drains", holders.replacements.candidates.state())
            put("sessions", JsonObject(sessions.mapValues { (_, session) -> JsonPrimitive(session.route.key()) }))
        }
        files.saveState(Rpc.encode(state))
    }

    fun reconcile() = replacements.reconcile()

    fun keepAlive() {
        replacements.finish()
        sessions.values.filter { it.route.launch.toIntOrNull()?.let(Ids::running) == false }.forEach {
            sessions.remove(it.id)
            it.stream?.close()
        }
        sessions.values.forEach { it.stream?.push(": keepalive\n\n") }
        holders.links.touch(relay.busy)
        save()
        timers.at(clock.now() + KEEPALIVE) { keepAlive() }
    }

    private fun changed() {
        save()
        replacements.finish()
    }
}

internal class HubReplacements(
    private val routes: Routes,
    private val holders: Holders,
    private val relay: Relay,
    private val timers: Timers,
    private val clock: Clock,
) {
    fun pause(server: String, launch: String): String? {
        val route = route(server, launch) ?: return missingRoute(server, launch)
        return holders.replacements.candidates.pause(route, holders.links.of(route))
    }

    fun replace(server: String, launch: String, done: (String?) -> Unit) {
        val route = route(server, launch)
            ?: return done(missingRoute(server, launch))
        val candidateReady = holders.replacements.candidates.ready(route).isNotEmpty()
        val retirementPending = holders.replacements.retirements.pending(route) != null
        if (candidateReady || retirementPending) {
            done(null)
            return
        }
        val drain = holders.replacements.candidates.drain(server, launch)
            ?: return done("this target is not paused for replacement")
        val oldSocket = drain.socket
        val oldGeneration = drain.generation
        routes.replacements.start(route, oldSocket, oldGeneration) { candidate, failure ->
            if (failure != null || candidate == null) {
                val words = failure ?: "the daemon returned no candidate"
                holders.replacements.candidates.candidateBlocked(route, words)
                return@start done(words)
            }
            done(
                holders.replacements.candidates.startCandidate(
                    route,
                    candidate,
                    holders.links::connectCandidate,
                ),
            )
        }
    }

    fun resume(server: String, launch: String): Boolean {
        val route = route(server, launch) ?: return false
        return holders.replacements.candidates.resume(route)
    }

    fun status(server: String, launch: String): JsonObject? {
        val drain = holders.replacements.candidates.drain(server, launch) ?: return null
        return holders.replacements.candidates.status(
            server,
            launch,
            relay.inFlight(drain.socket),
            relay.attachedClients(server, launch),
        )
    }

    private fun route(server: String, launch: String): Route? = routes.byKey("$server\n$launch")

    private fun missingRoute(server: String, launch: String): String =
        "no retained route for $server and launch '$launch'"

    fun finish() {
        cutOverReadyCandidates()
        retireOldCandidates()
        rollBackLostCandidates()
    }

    fun reconcile() {
        holders.replacements.candidates.all().forEach { drain ->
            val route = routes.byKey(drain.key()) ?: return@forEach
            val candidate = drain.candidate ?: return@forEach
            when (drain.phase) {
                COMMITTING -> replayCommit(route, drain, candidate)
                RETIRING, RETIRE_BLOCKED -> replayRetirement(route, drain, candidate)
                ROLLING_BACK -> replayRollback(route, drain, candidate)
                else -> Unit
            }
        }
        finish()
    }

    private fun replayCommit(route: Route, drain: HolderDrain, candidate: ReplacementCandidate) {
        routes.replacements.commit(route, drain.socket, drain.generation, candidate) { failure ->
            val routesMatch = holders.replacements.candidates.canRoute(route, candidate)
            if (failure != null || !routesMatch) {
                holders.replacements.candidates.candidateBlocked(
                    route,
                    failure ?: "the candidate was no longer live when cutover was confirmed",
                )
            } else {
                val moved = routes.replacements.replace(route, drain.socket, drain.generation, candidate)
                if (moved == null) {
                    holders.replacements.candidates.candidateBlocked(
                        route,
                        "the target changed before its candidate could take the settled route",
                    )
                } else {
                    holders.replacements.candidates.routed(route, candidate, moved)
                }
            }
        }
    }

    private fun replayRetirement(route: Route, drain: HolderDrain, candidate: ReplacementCandidate) {
        routes.replacements.retire(route, drain.socket, drain.generation, candidate) { failure ->
            if (failure == null) {
                holders.replacements.retirements.retired(route)
            } else {
                holders.replacements.retirements.blocked(route, failure)
                timers.at(clock.now() + RETIRE_RETRY) { holders.replacements.retirements.retry(route) }
            }
        }
    }

    private fun replayRollback(route: Route, drain: HolderDrain, candidate: ReplacementCandidate) {
        routes.replacements.rollback(route, drain.socket, drain.generation, candidate) { failure ->
            if (failure != null) {
                holders.replacements.retirements.rollbackUnknown(route, failure)
            } else if (!routes.replacements.restore(drain.moved, drain.socket, drain.generation, candidate)) {
                holders.replacements.retirements.rollbackUnknown(
                    route,
                    "the restored routes no longer match the dead candidate generation",
                )
            } else {
                holders.replacements.retirements.rolledBack(route)
            }
        }
    }

    private fun cutOverReadyCandidates() {
        holders.replacements.candidates.ready().forEach { drain ->
            val route = routes.byKey(drain.key()) ?: return@forEach
            if (relay.inFlight(drain.socket) != 0) return@forEach
            val candidate = holders.replacements.candidates.claimCandidate(route) ?: return@forEach
            routes.replacements.commit(route, drain.socket, drain.generation, candidate) { failure ->
                val routesMatch = holders.replacements.candidates.canRoute(route, candidate)
                if (failure != null || !routesMatch) {
                    holders.replacements.candidates.candidateBlocked(
                        route,
                        failure ?: "the candidate was no longer live when cutover was confirmed",
                    )
                    return@commit
                }
                val moved = routes.replacements.replace(route, drain.socket, drain.generation, candidate)
                if (moved == null) {
                    holders.replacements.candidates.candidateBlocked(
                        route,
                        "the target changed before its candidate could take the settled route",
                    )
                } else {
                    holders.replacements.candidates.routed(route, candidate, moved)
                }
            }
        }
    }

    private fun retireOldCandidates() {
        holders.replacements.retirements.candidates().forEach { drain ->
            val route = routes.byKey(drain.key()) ?: return@forEach
            val candidate = drain.candidate ?: return@forEach
            val live = holders.links.of(route)?.let {
                it.ready && it.link != null && it.generation == candidate.generation
            } == true
            val claimed = holders.replacements.retirements.claim(route, live) ?: return@forEach
            routes.replacements.retire(route, claimed.socket, claimed.generation, candidate) { failure ->
                if (failure == null) {
                    holders.replacements.retirements.retired(route)
                } else {
                    holders.replacements.retirements.blocked(route, failure)
                    timers.at(clock.now() + RETIRE_RETRY) { holders.replacements.retirements.retry(route) }
                }
            }
        }
    }

    private fun rollBackLostCandidates() {
        holders.replacements.retirements.rollbacks().forEach { drain ->
            val route = routes.byKey(drain.key()) ?: return@forEach
            val claimed = holders.replacements.retirements.claimRollback(route)
            if (claimed == null) {
                holders.replacements.retirements.rollbackUnknown(
                    route,
                    "the routed replacement has no exact moved-route record",
                )
                return@forEach
            }
            val candidate = claimed.candidate ?: return@forEach
            routes.replacements.rollback(route, claimed.socket, claimed.generation, candidate) { failure ->
                if (failure != null) {
                    holders.replacements.retirements.rollbackUnknown(route, failure)
                } else if (!routes.replacements.restore(claimed.moved, claimed.socket, claimed.generation, candidate)) {
                    holders.replacements.retirements.rollbackUnknown(
                        route,
                        "the restored routes no longer match the dead candidate generation",
                    )
                } else {
                    holders.replacements.retirements.rolledBack(route)
                }
            }
        }
    }
}

internal class HubHttp(
    private val files: HubFiles,
    private val sessions: MutableMap<String, Session>,
    private val routes: Routes,
    private val holders: Holders,
    private val relay: Relay,
    private val save: () -> Unit,
) {
    fun handle(conn: HttpConn, req: Request) {
        val server = req.path.removePrefix(PREFIX).takeIf { req.path.startsWith(PREFIX) && it.isNotEmpty() }
            ?: return conn.reply(NOT_FOUND, Rpc.error(JsonNull, "no such path"))
        if (!files.admits(req.path, req.headers)) return conn.reply(UNAUTHORIZED)
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
        if (holders.replacements.admissions.paused(route)) {
            return conn.reply(UNAVAILABLE, Rpc.error(id, DRAINING))
        }
        routes.resolve(route) { failure ->
            if (failure != null) return@resolve conn.reply(OK, Rpc.error(id, failure))
            if (holders.replacements.admissions.paused(route)) {
                return@resolve conn.reply(UNAVAILABLE, Rpc.error(id, DRAINING))
            }
            holders.links.knownInit(route) { init, why ->
                if (init == null) {
                    return@knownInit conn.reply(OK, Rpc.error(id, why ?: "the server did not start"))
                }
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
        if (answeredImmediately(conn, session.route, method, id)) return
        routes.resolve(session.route) { unresolved ->
            if (unresolved == null) {
                callResolved(conn, session, msg, id)
            } else {
                conn.reply(OK, Rpc.error(id, unresolved))
            }
        }
    }

    private fun answeredImmediately(conn: HttpConn, route: Route, method: String, id: JsonElement): Boolean {
        val paused = holders.replacements.admissions.paused(route)
        val cold = method == Rpc.TOOLS_LIST && holders.links.of(route)?.ready != true
        val kept = if (cold) files.listing(route.listing) else null
        when {
            method == "ping" -> conn.reply(OK, Rpc.result(id, JsonObject(emptyMap())))
            paused -> conn.reply(UNAVAILABLE, Rpc.error(id, DRAINING))
            kept != null -> conn.reply(OK, Rpc.result(id, kept))
        }
        return listOf(method == "ping", paused, kept != null).any { it }
    }

    private fun callResolved(conn: HttpConn, session: Session, msg: JsonObject, id: JsonElement) {
        val route = session.route
        holders.links.live(route) { failure ->
            val holder = holders.links.of(route)
            if (failure != null || holder == null) {
                conn.reply(OK, Rpc.error(id, failure ?: "no holder"))
            } else if (holders.replacements.admissions.paused(route)) {
                conn.reply(UNAVAILABLE, Rpc.error(id, DRAINING))
            } else {
                relay.call(holder, conn, session, msg, id)
            }
        }
    }

    private fun notification(conn: HttpConn, session: Session, msg: JsonObject, method: String) {
        conn.reply(ACCEPTED)
        if (method == "notifications/initialized") return
        val holder = holders.links.of(session.route)?.takeIf { it.ready } ?: return
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
private const val DRAINING = "this MCP target is paused for a safe replacement drain"
private const val SHORT = 8
private const val KEEPALIVE = 25_000L
private const val RETIRE_RETRY = 250L
