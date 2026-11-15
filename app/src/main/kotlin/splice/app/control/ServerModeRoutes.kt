// NEW: server mode (docs/specs/server-mode.md, F1 and F3). The control plane's routes on a server's keys and people,
// behind the management key on loopback, which is how `splice server` reaches the daemon that holds them:
//
//   GET    /api/server/keys[?person=]          every key, or one person's; no secret in it
//   POST   /api/server/keys                    mint one: {"person", "name"?, "expires"?}; the key is answered once
//   DELETE /api/server/keys/{prefix}           revoke one
//   DELETE /api/server/people/{person}/keys    offboarding: revoke every key of one person
//   PUT    /api/server/people/{person}         that person's own caps: {"inflight"?, "rpm"?}
//
// A daemon with no server mode answers each 409, saying why it has none.
package splice.app.control

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import splice.app.control.api.ControlBodies
import splice.app.control.mount.ControlGuard
import splice.http.JsonBody
import splice.http.JsonReply
import splice.server.ServerAnswer
import splice.server.ServerMode

/** How the routes reach the daemon's server mode: asked at each request, since the daemon opens it after the control
 *  server is built, and a daemon whose splice.toml has no `[server]` never has one. */
internal interface ServerModeSource {
    /** The running server mode, or null when this daemon has none. */
    fun mode(): ServerMode?

    /** Why [mode] is null, in words an operator can act on. */
    fun absent(): String
}

internal class ServerModeRoutes(private val ports: ConsolePorts, private val guard: ControlGuard) {
    private val jsonBody = JsonBody()

    fun register(route: Route) {
        route.get("/api/server/keys") { guard.guarded(call) { listKeys(call) } }
        route.post("/api/server/keys") { guard.guarded(call) { createKey(call) } }
        route.delete("/api/server/keys/{$PREFIX_PARAM}") { guard.guarded(call) { revokeKey(call) } }
        route.delete("/api/server/people/{$PERSON_PARAM}/keys") { guard.guarded(call) { revokePerson(call) } }
        route.put("/api/server/people/{$PERSON_PARAM}") { guard.guarded(call) { setPerson(call) } }
    }

    private suspend fun listKeys(call: ApplicationCall) {
        val mode = running(call) ?: return
        say(call, mode.keyApi.list(call.request.queryParameters[PERSON_PARAM]))
    }

    private suspend fun createKey(call: ApplicationCall) {
        val mode = running(call) ?: return
        say(call, mode.keyApi.create(jsonBody.parse(call)))
    }

    private suspend fun revokeKey(call: ApplicationCall) {
        val mode = running(call) ?: return
        say(call, mode.keyApi.revoke(call.parameters[PREFIX_PARAM].orEmpty()))
    }

    private suspend fun revokePerson(call: ApplicationCall) {
        val mode = running(call) ?: return
        say(call, mode.keyApi.revokePerson(call.parameters[PERSON_PARAM].orEmpty()))
    }

    private suspend fun setPerson(call: ApplicationCall) {
        val mode = running(call) ?: return
        say(call, mode.personApi.set(call.parameters[PERSON_PARAM].orEmpty(), jsonBody.parse(call)))
    }

    /** The running server mode, or null with the 409 answered. */
    private suspend fun running(call: ApplicationCall): ServerMode? {
        val source = ports.serverMode
        val mode = source?.mode()
        if (mode == null) refuse(call, HttpStatusCode.Conflict, source?.absent() ?: NO_SERVER_SOURCE)
        return mode
    }

    private suspend fun say(call: ApplicationCall, answer: ServerAnswer) {
        when (answer) {
            is ServerAnswer.Done -> JsonReply(answer.status, answer.body.toString()).send(call)
            is ServerAnswer.Refused -> refuse(call, answer.status, answer.why)
        }
    }

    private suspend fun refuse(call: ApplicationCall, status: HttpStatusCode, why: String) {
        JsonReply(status, ControlBodies.errorJson(why)).send(call)
    }
}

private const val PREFIX_PARAM = "prefix"
private const val PERSON_PARAM = "person"

// What a control server answers when no daemon handed it a server mode to ask, as in a test that builds one bare.
private const val NO_SERVER_SOURCE = "server mode is not available in this build"
