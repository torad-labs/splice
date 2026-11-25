// NEW: the modes' routes (modes/README.md): `GET /api/modes` lists every mode splice found, whether each is on for
// every head and per head, and its tools, behind the management key. A switch of an mcp mode brings Claude Desktop's
// configuration up to date with it (DaemonDesktop.publishAgain), as a daemon start does.
package splice.app.modes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import splice.app.control.ConsolePorts
import splice.app.control.api.ControlBodies
import splice.app.control.mount.ControlGuard
import splice.app.control.mount.ControlReplies
import splice.core.util.Cancellables
import splice.core.util.TopologyRefusal
import splice.http.JsonReply
import splice.modes.api.ModeKind

private const val MODES_NOT_LOADED = "this daemon has not loaded its modes"

/** [ports] is read at call time: ControlPlane assigns [ConsolePorts.modes] after construction. */
internal class ModesMount(private val ports: ConsolePorts, private val guard: ControlGuard) {
    fun register(route: Route) {
        route.get("/api/modes") {
            guard.guarded(call) { withModes(call) { ControlReplies.respond(call, ModeRows(it).all().toString()) } }
        }
        route.put("/api/modes/{name}") { guard.guarded(call) { withModes(call) { switch(call, it) } } }
    }

    /** Every mode's own routes, under their exact paths, behind the same key, registered after every route of
     *  splice's own, which they are checked against first: a path taken refuses the daemon, every claim at once. The
     *  routing is built when the server starts, after ControlPlane assigned the modes. */
    fun registerRoutes(route: Route) {
        val modes = ports.modes ?: return
        val claims = (route as? RoutingNode)?.let { ModeClaims(it).findings(modes.catalog.modes) }.orEmpty()
        if (claims.isNotEmpty()) throw TopologyRefusal(claims)
        modes.catalog.modes.forEach { mode ->
            mode.handler.routes.forEach { declared ->
                val served = ModeRouteCall(mode.name, declared, modes.store(mode.name))
                route.route(declared.path, HttpMethod.parse(declared.method.uppercase())) {
                    handle { guard.guarded(call) { served.answer(call) } }
                }
            }
        }
    }

    /** PUT /api/modes/<name>: the switch written, and the mode's row as it is after it. */
    private suspend fun switch(call: ApplicationCall, modes: DaemonModes) {
        val name = call.parameters["name"].orEmpty()
        val body = call.receiveText().takeIf(String::isNotBlank)?.let { text ->
            Cancellables.runCatchingCancellable { Json.parseToJsonElement(text) }.getOrElse { JsonNull }
        }
        when (val switched = ModeSwitch(modes).put(name, body, ports.topology)) {
            is Switched.Done -> {
                // An mcp mode is one of Claude Desktop's MCP servers while it is on: its configuration follows now,
                // for Desktop to read at its next start.
                if (switched.mode.manifest.kind == ModeKind.MCP) ports.desktop?.publishAgain()
                ControlReplies.respond(call, ModeRows(modes).row(switched.mode).toString())
            }
            is Switched.Refused -> JsonReply(
                HttpStatusCode.fromValue(switched.status),
                ControlBodies.errorJson(switched.error),
            ).send(call)
        }
    }

    private suspend fun withModes(call: ApplicationCall, answer: ModesAnswer) {
        val modes = ports.modes
        if (modes == null) {
            JsonReply(HttpStatusCode.ServiceUnavailable, ControlBodies.errorJson(MODES_NOT_LOADED)).send(call)
        } else {
            answer.with(modes)
        }
    }
}

/** What a modes route answers, given the daemon's modes. */
internal fun interface ModesAnswer {
    suspend fun with(modes: DaemonModes)
}
