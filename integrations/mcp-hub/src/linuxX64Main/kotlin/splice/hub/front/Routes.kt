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
    var shared = true
    var listing: String? = null
    val waiters = ArrayList<(String?) -> Unit>()

    fun key(): String = "$server\n$launch"
}

class Routes(
    private val daemon: Daemon,
    private val timers: Timers,
    private val clock: Clock,
    private val changed: () -> Unit,
) {
    private val known = HashMap<String, Route>()

    fun of(server: String, launch: String): Route = Route(server, launch).let { known.getOrPut(it.key()) { it } }

    fun byKey(key: String): Route? = known[key]

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

    fun restore(saved: JsonObject?) {
        saved?.forEach { (key, v) ->
            val o = v as? JsonObject ?: return@forEach
            val route = Route(Rpc.str(o, SERVER) ?: return@forEach, Rpc.str(o, LAUNCH).orEmpty())
            route.socket = Rpc.str(o, SOCKET)
            route.shared = Rpc.str(o, SHARED) != "false"
            route.listing = Rpc.str(o, LISTING)
            known[key] = route
        }
    }

    fun state(): JsonObject =
        JsonObject(
            known.mapValues { (_, r) ->
                buildJsonObject {
                    put(SERVER, r.server)
                    put(LAUNCH, r.launch)
                    put(SOCKET, r.socket)
                    put(SHARED, r.shared)
                    put(LISTING, r.listing)
                }
            },
        )

    /** A daemon call retried while the daemon cannot be reached, until [until]. */
    private fun ask(route: Route, path: String, until: Long, then: (Int, String) -> Unit) {
        val body = buildJsonObject {
            put(SERVER, route.server)
            put(LAUNCH, route.launch)
        }
        daemon.call(path, Rpc.encode(body)) { status, text ->
            val unreachable = status == 0 || status == UNAVAILABLE
            if (unreachable && clock.now() < until) {
                timers.at(clock.now() + RETRY) { ask(route, path, until, then) }
            } else {
                then(status, text)
            }
        }
    }

    private fun place(route: Route, body: String) {
        val o = JsonText.objectOrNull(body) ?: return
        route.socket = Rpc.str(o, SOCKET) ?: route.socket
        route.shared = Rpc.str(o, SHARED) != "false"
        route.listing = Rpc.str(o, LISTING)
        changed()
    }
}

private const val SERVER = "server"
private const val LAUNCH = "launch"
private const val SOCKET = "socket"
private const val SHARED = "shared"
private const val LISTING = "listing"
private const val RESOLVE_BUDGET = 30_000L
private const val START_BUDGET = 60_000L
private const val RETRY = 250L
