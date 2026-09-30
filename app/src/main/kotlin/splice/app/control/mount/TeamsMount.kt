// NEW: V4-444 — the teams capability's routes: the split team reads and the team writes (V4-131, FEATURES.md
// 6.1), over the session registry. Split from SessionsMount, which keeps the registry's own routes.
package splice.app.control.mount

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import splice.app.control.ConsolePorts
import splice.sessions.http.ActivitySource
import splice.sessions.http.SentTextSource
import splice.sessions.http.TeamSource
import splice.sessions.http.TeamsRoutes
import splice.sessions.registry.SessionSource

/** Registered only when a session registry is wired, as the team routes always were. [ports] is read at CALL
 *  time: ControlPlane assigns the activity and team stores after construction. */
internal class TeamsMount(
    wiring: SessionsWiring,
    sessions: SessionSource?,
    ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val teams = wiring.routes?.let { routes ->
        TeamsRoutes(
            TeamSource { ports.teams },
            wiring.sessionHeads,
            sessions,
            ActivitySource { ports.activity },
            SentTextSource(routes::sentTexts),
        )
    }

    /** There is deliberately no GET /api/teams/{id}; the board composes from these and /api/sessions. */
    fun register(route: Route) {
        teams?.let { registerTeams(route, it) }
    }

    private fun registerTeams(route: Route, teams: TeamsRoutes) {
        route.get("/api/teams") { guard.guarded(call) { teams.list().send(call) } }
        route.put("/api/teams") {
            guard.guarded(call) {
                teams.create(call.receiveText(), call.request.headers["Idempotency-Key"]).send(call)
            }
        }
        route.put("/api/teams/{id}") { guard.guarded(call) { teams.replace(id(call), call.receiveText()).send(call) } }
        route.put("/api/teams/{id}/sessions") {
            guard.guarded(call) { teams.bind(id(call), call.receiveText()).send(call) }
        }
        route.put("/api/teams/{id}/slots/{slot}/instructions") {
            guard.guarded(call) {
                teams.instruct(id(call), call.parameters["slot"].orEmpty(), call.receiveText()).send(call)
            }
        }
        route.post("/api/teams/{id}/archive") { guard.guarded(call) { teams.archive(id(call)).send(call) } }
        route.get("/api/teams/{id}/edges") { guard.guarded(call) { teams.reads.edges(id(call)).send(call) } }
        // V4-249: ?from=&to= is the caller's own day (the console's local one), beside ?day=, a UTC date.
        route.get("/api/teams/{id}/chat") {
            guard.guarded(call) {
                val query = call.request.queryParameters
                teams.reads.chat(id(call), query["day"], query["from"], query["to"]).send(call)
            }
        }
        route.get("/api/teams/{id}/activity") {
            guard.guarded(call) {
                val query = call.request.queryParameters
                teams.reads.activity(id(call), query["day"], query["from"], query["to"]).send(call)
            }
        }
        route.get("/api/teams/{id}/economics") { guard.guarded(call) { teams.economics(id(call)).send(call) } }
    }

    private fun id(call: ApplicationCall): String = call.parameters["id"].orEmpty()
}
