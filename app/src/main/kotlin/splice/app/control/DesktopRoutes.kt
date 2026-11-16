// NEW: desktop-door (docs/specs/desktop-door.md). The control port's routes for Claude Desktop's Code tab:
//
//   POST /desktop/v1/messages[?beta=true]               a turn, behind the desktop key and no other
//   POST /desktop/v1/messages/count_tokens[?beta=true]  a count, behind the desktop key and no other
//   GET  /api/desktop                                   what `splice desktop` says, behind the management key
//   POST /api/desktop/on                                {"model"?}: `splice desktop on [--model]`
//   POST /api/desktop/off                               `splice desktop off`
//   POST /api/desktop/rotate-key                        `splice desktop rotate-key`
//
// The door is on the control port, whose number splice.toml fixes, so its address is the same at every start. The
// desktop key opens only the two door routes, and the management and turn keys do not open them.
package splice.app.control

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.control.api.ControlBodies
import splice.app.control.mount.ControlGuard
import splice.app.desktop.DESKTOP_PATH
import splice.app.desktop.DesktopAct
import splice.app.desktop.DesktopService
import splice.core.util.JsonScalars
import splice.http.JsonBody
import splice.http.JsonReply

internal class DesktopRoutes(private val ports: ConsolePorts, private val guard: ControlGuard) {
    private val jsonBody = JsonBody()

    fun register(route: Route) {
        route.post("$DESKTOP_PATH/v1/messages") {
            val desktop = opened(call) ?: return@post
            desktop.door.guarded(call) { desktop.door.messages(call) }
        }
        route.post("$DESKTOP_PATH/v1/messages/count_tokens") {
            val desktop = opened(call) ?: return@post
            desktop.door.guarded(call) { desktop.door.countTokens(call) }
        }
        route.get("/api/desktop") { guard.guarded(call) { state(call) } }
        route.post("/api/desktop/on") { guard.guarded(call) { on(call) } }
        route.post("/api/desktop/off") { guard.guarded(call) { act(call) { it.switch.off() } } }
        route.post("/api/desktop/rotate-key") { guard.guarded(call) { act(call) { it.switch.rotate() } } }
    }

    private suspend fun state(call: ApplicationCall) {
        val desktop = opened(call) ?: return
        JsonReply(HttpStatusCode.OK, desktop.state().toString()).send(call)
    }

    private suspend fun on(call: ApplicationCall) {
        val model = JsonScalars.str(jsonBody.parse(call), "model")?.ifEmpty { null }
        act(call) { it.switch.on(model) }
    }

    /** [doing] done, answered with what changed and the state after it, or refused with nothing changed. */
    private suspend fun act(call: ApplicationCall, doing: DesktopDoing) {
        val desktop = opened(call) ?: return
        when (val done = doing(desktop)) {
            is DesktopAct.Refused ->
                JsonReply(HttpStatusCode.fromValue(done.status), ControlBodies.errorJson(done.why)).send(call)
            is DesktopAct.Done -> {
                val said = mapOf("said" to JsonPrimitive(done.said), "default_from" to JsonPrimitive(done.default))
                JsonReply(HttpStatusCode.OK, JsonObject(desktop.state() + said).toString()).send(call)
            }
        }
    }

    /** The daemon's door, or null with a 503 answered while the daemon has not opened it. */
    private suspend fun opened(call: ApplicationCall): DesktopService? {
        val desktop = ports.desktop?.opened
        if (desktop == null) JsonReply(HttpStatusCode.ServiceUnavailable, ControlBodies.errorJson(NOT_OPEN)).send(call)
        return desktop
    }
}

/** One act of `splice desktop` on the daemon's door. */
internal fun interface DesktopDoing {
    suspend operator fun invoke(desktop: DesktopService): DesktopAct
}

// What the routes answer before the daemon has opened the door: its heads are still starting.
private const val NOT_OPEN = "splice desktop: the door opens once the daemon's heads are up"
