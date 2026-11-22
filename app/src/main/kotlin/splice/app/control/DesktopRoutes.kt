// NEW: desktop-door (docs/specs/desktop-door.md). The control port's routes for the door and for the two commands
// that drive it:
//
//   POST /door/v1/messages[?beta=true]                  a turn, behind Desktop's key or the terminal's and no other
//   POST /door/v1/messages/count_tokens[?beta=true]     a count, behind the same two keys
//   GET  /door/v1/models[?limit=&after_id=]             the Splice gateway's model list, as Claude Desktop's and
//                                                       Claude Code's discovery read it (DoorCatalog/GatewayModels),
//                                                       behind the same two keys
//   ... /desktop/v1/...                                 the same three routes at the door's path before it had two
//                                                       callers, for a Desktop set up then
//   GET  /api/desktop                                   what `splice desktop` says, behind the management key
//   POST /api/desktop/on                                {"model"?}: `splice desktop on [--model]`, which puts
//                                                       splice's configuration in Claude Desktop (desktop-full)
//   POST /api/desktop/off                               `splice desktop off`
//   POST /api/desktop/rotate-key                        `splice desktop rotate-key`
//   POST /api/desktop/publish                           re-publish inferenceModels into every Desktop library on the
//                                                       door, including newly created side-by-side folders
//   GET  /api/claude                                    what `splice claude` says (docs/specs/door-default.md)
//   POST /api/claude/door                               {"model"?}: `splice claude door [--model]`, linking
//                                                       `claude` to splice where it is not yet (ClaudeLink)
//   POST /api/claude/head                               `splice claude head`
//
// The door is on the control port, whose number splice.toml fixes, so its address is the same at every start. The
// desktop key and the terminal's key each open only the door's routes, and the management and turn keys do not open
// them.
package splice.app.control

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.api.ControlBodies
import splice.app.control.mount.ControlGuard
import splice.app.desktop.DESKTOP_ERROR_PREFIX
import splice.app.desktop.DESKTOP_PATH
import splice.app.desktop.DOOR_PATH
import splice.app.desktop.DesktopService
import splice.app.desktop.setup.DesktopAct
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.http.JsonBody
import splice.http.JsonReply
import splice.share.RefusalVoice

internal class DesktopRoutes(
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    private val link: ClaudeLink,
) {
    private val jsonBody = JsonBody()
    private val voice = RefusalVoice(DESKTOP_ERROR_PREFIX)

    fun register(route: Route) {
        listOf(DOOR_PATH, DESKTOP_PATH).forEach { at ->
            route.post("$at/v1/messages") {
                val desktop = atDoor(call) ?: return@post
                desktop.door.messages(call)
            }
            route.post("$at/v1/messages/count_tokens") {
                val desktop = atDoor(call) ?: return@post
                desktop.door.countTokens(call)
            }
            route.get("$at/v1/models") {
                val desktop = atDoor(call) ?: return@get
                desktop.door.models(call)
            }
        }
        route.get("/api/desktop") { guard.guarded(call) { state(call) } }
        route.post("/api/desktop/on") { guard.guarded(call) { on(call) } }
        route.post("/api/desktop/off") { guard.guarded(call) { act(call) { it.switch.off() } } }
        route.post("/api/desktop/rotate-key") { guard.guarded(call) { act(call) { it.switch.rotate() } } }
        route.post("/api/desktop/publish") { guard.guarded(call) { publish(call) } }
        route.get("/api/claude") { guard.guarded(call) { claudeState(call) } }
        route.post("/api/claude/door") { guard.guarded(call) { toDoor(call) } }
        route.post("/api/claude/head") { guard.guarded(call) { act(call, CLAUDE) { it.claude.toHead() } } }
    }

    private suspend fun state(call: ApplicationCall) {
        val desktop = opened(call) ?: return
        JsonReply(HttpStatusCode.OK, desktop.state().toString()).send(call)
    }

    private suspend fun claudeState(call: ApplicationCall) {
        val desktop = opened(call) ?: return
        JsonReply(HttpStatusCode.OK, desktop.claudeState().toString()).send(call)
    }

    private suspend fun toDoor(call: ApplicationCall) {
        val model = JsonScalars.str(jsonBody.parse(call), "model")?.ifEmpty { null }
        act(call, CLAUDE) { desktop ->
            when (val done = desktop.claude.toDoor(model)) {
                is DesktopAct.Done -> done.copy(said = link.linked(done.said))
                is DesktopAct.Refused -> done
            }
        }
    }

    private suspend fun on(call: ApplicationCall) {
        val model = JsonScalars.str(jsonBody.parse(call), "model")?.ifEmpty { null }
        act(call) { it.switch.on(model) }
    }

    private suspend fun publish(call: ApplicationCall) {
        if (opened(call) == null) return
        ports.desktop?.publishAgain()
        JsonReply(HttpStatusCode.OK, buildJsonObject { put("ok", true) }.toString()).send(call)
    }

    /** [doing] done, answered with what changed and the state after it ([of]'s), or refused with nothing changed. */
    private suspend fun act(call: ApplicationCall, of: DoorState = DESKTOP, doing: DesktopDoing) {
        val desktop = opened(call) ?: return
        when (val done = doing(desktop)) {
            is DesktopAct.Refused ->
                JsonReply(HttpStatusCode.fromValue(done.status), ControlBodies.errorJson(done.why)).send(call)
            is DesktopAct.Done -> {
                val said = mapOf("said" to JsonPrimitive(done.said), "default_from" to JsonPrimitive(done.default))
                JsonReply(HttpStatusCode.OK, JsonObject(of(desktop) + said).toString()).send(call)
            }
        }
    }

    /** The door a caller knocks at, or null with a 401 answered where this control plane has none. No key opens a door
     *  that is not there, so the caller is refused as one without the desktop key is at an open door: a route that
     *  said "not open" before it asked for a key would tell anyone on the port what only the key's holder may learn.
     *  The daemon opens its door before the control port binds, so its own callers never meet this. */
    private suspend fun atDoor(call: ApplicationCall): DesktopService? {
        val desktop = ports.desktop?.opened
        if (desktop == null) voice.refuse(call, HttpStatus.UNAUTHORIZED, NO_DOOR)
        return desktop
    }

    /** The daemon's door for `splice desktop`'s own routes, which the management key has already opened, or null with
     *  a 503 answered while the daemon has not opened it. */
    private suspend fun opened(call: ApplicationCall): DesktopService? {
        val desktop = ports.desktop?.opened
        if (desktop == null) JsonReply(HttpStatusCode.ServiceUnavailable, ControlBodies.errorJson(NOT_OPEN)).send(call)
        return desktop
    }
}

/** What `splice desktop` or `splice claude` says of the daemon's door. */
internal fun interface DoorState {
    suspend operator fun invoke(desktop: DesktopService): JsonObject
}

private val DESKTOP = DoorState { it.state() }
private val CLAUDE = DoorState { it.claudeState() }

/** One act of `splice desktop` on the daemon's door. */
internal fun interface DesktopDoing {
    suspend operator fun invoke(desktop: DesktopService): DesktopAct
}

// What the routes answer before the daemon has opened the door: its heads are still starting.
private const val NOT_OPEN = "splice desktop: the door opens once the daemon's heads are up"

// What a caller at a door that is not there is told, after the door's own prefix.
private const val NO_DOOR = "no door is open here; `splice desktop` says whether Desktop is on"
