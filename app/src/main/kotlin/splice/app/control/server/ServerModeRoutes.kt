// NEW: server mode (docs/specs/server-mode.md, F1, F3, F4 and F12). The control plane's routes on a server's keys, people,
// teams and upstreams, behind the management key on loopback, which is how `splice server` reaches the daemon that holds
// them:
//
//   GET    /api/server/keys[?person=]          every key, or one person's; no secret in it
//   POST   /api/server/keys                    mint one: {"person", "name"?, "expires"?}; the key is answered once
//   DELETE /api/server/keys/{prefix}           revoke one
//   DELETE /api/server/people/{person}/keys    offboarding: revoke every key of one person
//   PUT    /api/server/people/{person}         that person's own settings: {"inflight"?, "rpm"?, "upstream"?, "team"?,
//                                              "commands"?}
//   PUT    /api/server/teams/{team}            that team's settings: {"commands"} (F12)
//   GET    /api/server/upstreams               every upstream: provider, name, url, healthy, inflight
//
// A daemon with no server mode answers each 409, saying why it has none (ServerModeReplies). The ledger's routes are
// ServerLedgerRoutes, and ServerMount registers both.
package splice.app.control.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import splice.app.control.mount.ControlGuard
import splice.http.JsonBody
import splice.server.ServerAnswer
import splice.server.ServerMode
import splice.server.audit.DEFAULT_AUDIT_TAIL

internal class ServerModeRoutes(private val replies: ServerModeReplies, private val guard: ControlGuard) {
    private val jsonBody = JsonBody()

    fun register(route: Route) {
        route.get("/api/server/keys") { guard.guarded(call) { listKeys(call) } }
        route.post("/api/server/keys") { guard.guarded(call) { createKey(call) } }
        route.delete("/api/server/keys/{$PREFIX_PARAM}") { guard.guarded(call) { revokeKey(call) } }
        route.delete("/api/server/people/{$PERSON_PARAM}/keys") { guard.guarded(call) { revokePerson(call) } }
        route.put("/api/server/people/{$PERSON_PARAM}") { guard.guarded(call) { setPerson(call) } }
        route.put("/api/server/teams/{$TEAM_PARAM}") { guard.guarded(call) { setTeam(call) } }
        route.get("/api/server/upstreams") { guard.guarded(call) { listUpstreams(call) } }
        route.get("/api/server/audit") { guard.guarded(call) { listAudit(call) } }
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

    private suspend fun setTeam(call: ApplicationCall) {
        val mode = running(call) ?: return
        say(call, mode.teamApi.set(call.parameters[TEAM_PARAM].orEmpty(), jsonBody.parse(call)))
    }

    private suspend fun listUpstreams(call: ApplicationCall) {
        val mode = running(call) ?: return
        say(call, mode.upstreamApi.list())
    }

    private suspend fun listAudit(call: ApplicationCall) {
        val mode = running(call) ?: return
        val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: DEFAULT_AUDIT_TAIL
        say(call, mode.audit.list(tail))
    }

    private suspend fun running(call: ApplicationCall): ServerMode? = replies.running(call)

    private suspend fun say(call: ApplicationCall, answer: ServerAnswer) = replies.say(call, answer)
}

private const val PREFIX_PARAM = "prefix"
private const val PERSON_PARAM = "person"
private const val TEAM_PARAM = "team"
