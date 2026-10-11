// NEW: V4-444 — the teams capability's routes: the split team reads and the team writes (V4-131, FEATURES.md
// 6.1), over the session registry. Split from SessionsMount, which keeps the registry's own routes.
package splice.app.control.mount

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.core.config.UserHome
import splice.core.util.JsonScalars
import splice.sessions.http.ActivitySource
import splice.sessions.http.SentTextSource
import splice.sessions.http.TeamScreen
import splice.sessions.http.TeamSource
import splice.sessions.http.TeamStart
import splice.sessions.http.TeamsRoutes
import splice.sessions.registry.SessionSource
import splice.upstream.codemode.ProcessWaiter

/** Registered only when a session registry is wired, as the team routes always were. [ports] is read at CALL
 *  time: ControlPlane assigns the activity and team stores after construction. */
internal class TeamsMount(
    wiring: SessionsWiring,
    sessions: SessionSource?,
    heads: Map<String, ManagedHead>,
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

    /** Starting and stopping a member: the terminal and the team store are read per call, so a daemon that
     *  wires them after this mount is constructed still serves them (the [ports] rule). */
    private val starts = TeamStart(
        teams = TeamSource { ports.teams },
        drive = wiring.drive,
        commands = HeadStartCommands(heads),
        pins = PoolAccountPins(heads),
        arrival = RegistryArrival(sessions, ProcessWaiter()),
        registry = sessions,
        home = UserHome.dir(),
    )

    /** What a member's screen is offering right now, read through Sessions' own drive, launch records included. */
    private val screen = TeamScreen(TeamSource { ports.teams }, wiring.drive)

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
        // The three writes that drive a member: start it, stop its turn, answer what it is waiting on. The terminal
        // behind all three is SessionTerminal's contract, read per call.
        route.post("/api/teams/{id}/slots/{slot}/start") {
            guard.guarded(call) { starts.start(id(call), slot(call)).send(call) }
        }
        route.post("/api/teams/{id}/slots/{slot}/stop") {
            guard.guarded(call) { starts.stop(id(call), slot(call)).send(call) }
        }
        route.post("/api/teams/{id}/slots/{slot}/answer") {
            guard.guarded(call) { starts.answer(id(call), slot(call), choice(call.receiveText())).send(call) }
        }
        // The choices a member is showing, as its own client drew them (ScreenChoices): the card draws Allow /
        // Always allow / Deny because they are on the screen, never because splice keeps a list of them.
        route.get("/api/teams/{id}/slots/{slot}/screen") {
            guard.guarded(call) { screen.offer(id(call), slot(call)).send(call) }
        }
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

    private fun slot(call: ApplicationCall): String = call.parameters["slot"].orEmpty()

    /** The numbered choice a body names. A body that names none answers 0, which the route refuses in words. */
    private fun choice(body: String): Int {
        val asked = JsonScalars.objectOrNull(Json, body) ?: return 0
        return JsonScalars.long(asked, "choice")?.toInt() ?: 0
    }
}
