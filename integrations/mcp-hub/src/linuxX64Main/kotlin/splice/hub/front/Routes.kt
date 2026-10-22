// The daemon's side of routing: where each server runs for each launch, asked once and kept, and a start when nothing
// listens. Every daemon call is retried while the daemon cannot be reached, up to a budget, so a daemon swap delays
// a session's first call and never fails it.
package splice.hub.front

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where one server for one launch runs, as the daemon said. */
data class Route(val server: String, val launch: String) {
    var socket: String? = null
    var generation: String? = null
    var shared = true
    var listing: String? = null
    val waiters = ArrayList<(String?) -> Unit>()

    fun key(): String = "$server\n$launch"
}

/** Candidate holder supplied by the daemon from its current canonical configured tuple. */
data class ReplacementCandidate(val socket: String, val tuple: String, val generation: String, val shared: Boolean)

class Routes(
    private val daemon: Daemon,
    private val timers: Timers,
    private val clock: Clock,
    private val changed: () -> Unit,
) {
    private val known = HashMap<String, Route>()
    private val toldRoots = HashSet<String>()
    internal val replacements = RouteReplacements(known, daemon, timers, clock, changed)

    fun of(server: String, launch: String): Route = Route(server, launch).let { known.getOrPut(it.key()) { it } }

    fun byKey(key: String): Route? = known[key]

    fun generation(socket: String): String? = known.values.firstOrNull { it.socket == socket }?.generation

    /** Forgets where the holder at [socket] was, once it is gone: the next call asks the daemon again, so a launch
     *  follows a changed plan (a new sharing choice, a new server version) instead of the socket it resolved first. */
    fun forget(socket: String) {
        known.values.filter { it.socket == socket }.forEach {
            it.socket = null
            it.generation = null
        }
        changed()
    }

    /** The daemon's answer for where [route]'s server runs; asked once, then kept. Waits while the daemon is down. */
    fun resolve(route: Route, then: (String?) -> Unit) {
        if (route.socket != null) return then(null)
        route.waiters += then
        if (route.waiters.size > 1) return
        ask(route, "/api/mcp/hub/resolve", clock.now() + RESOLVE_BUDGET) { status, body ->
            val words = resolved(route, status, body)
            val waiting = route.waiters.toList()
            route.waiters.clear()
            waiting.forEach { it(words) }
        }
    }

    /** The daemon's resolve answer placed on [route]: null when it says where, else the words for the session. */
    private fun resolved(route: Route, status: Int, body: String): String? =
        when (status) {
            OK -> null.also { place(route, body) }
            NOT_FOUND -> Rpc.daemonWords(body) ?: "splice does not run ${route.server} for this session"
            else -> Rpc.daemonWords(body) ?: "the splice daemon did not answer where ${route.server} runs"
        }

    /** Asks the daemon to start [route]'s holder: [then] gets null once it listens, else the daemon's words. */
    fun start(route: Route, then: (String?) -> Unit) {
        ask(route, "/api/mcp/hub/start", clock.now() + START_BUDGET) { status, body ->
            if (status == OK) {
                place(route, body)
                then(null)
            } else {
                then(Rpc.daemonWords(body) ?: "the splice daemon did not start ${route.server}")
            }
        }
    }

    /** Hands the daemon a server's first tools/list answer, kept for the next session's listing. */
    fun keepListing(route: Route, init: JsonObject, tools: JsonElement) {
        val body = buildJsonObject {
            put(SERVER, route.server)
            put(LAUNCH, route.launch)
            put("initialize", init)
            put("tools", tools)
        }
        daemon.call("/api/mcp/hub/listing", Rpc.encode(body)) { _, _ -> }
    }

    /** Tells the daemon, once per route, that its shared server asked for roots, so the planner records it and the next
     *  connection starts a per-session copy. A lost call is told again the next time the server asks. */
    fun rootsAsked(route: Route) {
        if (!toldRoots.add(route.key())) return
        val body = buildJsonObject {
            put(SERVER, route.server)
            put(LAUNCH, route.launch)
        }
        daemon.call("/api/mcp/hub/roots", Rpc.encode(body)) { status, _ ->
            if (status !in OK..NO_CONTENT) toldRoots.remove(route.key())
        }
    }

    fun restore(saved: JsonObject?) {
        saved?.forEach { (key, value) ->
            val routeBody = value as? JsonObject ?: return@forEach
            val route = Route(Rpc.str(routeBody, SERVER) ?: return@forEach, Rpc.str(routeBody, LAUNCH).orEmpty())
            route.socket = Rpc.str(routeBody, SOCKET)
            route.generation = Rpc.str(routeBody, GENERATION)
            route.shared = Rpc.str(routeBody, SHARED) != "false"
            route.listing = Rpc.str(routeBody, LISTING)
            known[key] = route
        }
    }

    /** Older front state lacked generations, so refresh only those retained routes before any link loss needs settlement. */
    fun refreshGenerations() {
        known.values.filter { it.socket != null && it.generation == null }.forEach { route ->
            ask(route, "/api/mcp/hub/resolve", clock.now() + RESOLVE_BUDGET) { status, body ->
                if (status == OK) place(route, body)
            }
        }
    }

    fun state(): JsonObject =
        JsonObject(
            known.mapValues { (_, route) ->
                buildJsonObject {
                    put(SERVER, route.server)
                    put(LAUNCH, route.launch)
                    put(SOCKET, route.socket)
                    put(GENERATION, route.generation)
                    put(SHARED, route.shared)
                    put(LISTING, route.listing)
                }
            },
        )

    /** A daemon call retried while the daemon cannot be reached, until [until]. */
    private fun ask(
        route: Route,
        path: String,
        until: Long,
        extra: JsonObject = JsonObject(emptyMap()),
        then: (Int, String) -> Unit,
    ) {
        val body = buildJsonObject {
            put(SERVER, route.server)
            put(LAUNCH, route.launch)
            extra.forEach { (key, value) -> put(key, value) }
        }
        daemon.call(path, Rpc.encode(body)) { status, text ->
            val unreachable = status == 0 || status == UNAVAILABLE
            if (unreachable && clock.now() < until) {
                timers.at(clock.now() + RETRY) { ask(route, path, until, extra, then) }
            } else {
                then(status, text)
            }
        }
    }

    private fun place(route: Route, body: String) {
        val parsed = JsonText.objectOrNull(body) ?: return
        route.socket = Rpc.str(parsed, SOCKET) ?: route.socket
        route.generation = Rpc.str(parsed, GENERATION)
        route.shared = Rpc.str(parsed, SHARED) != "false"
        route.listing = Rpc.str(parsed, LISTING)
        changed()
    }
}

internal const val SERVER = "server"
internal const val LAUNCH = "launch"
internal const val SOCKET = "socket"
internal const val SHARED = "shared"
internal const val LISTING = "listing"
internal const val OLD_SOCKET = "old_socket"
internal const val OLD_GENERATION = "old_generation"
internal const val CANDIDATE_SOCKET = "candidate_socket"
internal const val CANDIDATE_TUPLE = "candidate_tuple"
internal const val CANDIDATE_GENERATION = "candidate_generation"
internal const val TUPLE = "tuple"
internal const val GENERATION = "generation"
internal const val REPLACEMENT_START = "/api/mcp/hub/replacement/start"
internal const val REPLACEMENT_COMMIT = "/api/mcp/hub/replacement/commit"
internal const val REPLACEMENT_RETIRE = "/api/mcp/hub/replacement/retire"
internal const val REPLACEMENT_ROLLBACK = "/api/mcp/hub/replacement/rollback"
internal const val REPLACEMENT_EXIT = "/api/mcp/hub/replacement/exit"
internal const val RESOLVE_BUDGET = 30_000L
internal const val START_BUDGET = 60_000L
internal const val RETRY = 250L
internal const val EXIT_RETRY = 250L
internal const val NO_CONTENT = 204
