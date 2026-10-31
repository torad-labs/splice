// NEW: V4-444 — the teams capability's routes: the split team reads and the team writes (V4-131, FEATURES.md
// 6.1), over the session registry. Split from SessionsMount, which keeps the registry's own routes.
package splice.app.control.mount

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.core.config.UserHome
import splice.core.util.JsonScalars
import splice.sessions.end.SessionEnd
import splice.sessions.end.TeamEnd
import splice.sessions.http.ActivitySource
import splice.sessions.http.ProjectAt
import splice.sessions.http.ProjectLookup
import splice.sessions.http.ProjectOf
import splice.sessions.http.SentTextSource
import splice.sessions.http.TeamScreen
import splice.sessions.http.TeamSource
import splice.sessions.http.TeamStart
import splice.sessions.http.TeamsRoutes
import splice.sessions.registry.SessionSource
import splice.upstream.codemode.ProcessDispatchers
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
    private val blockingIo = ProcessDispatchers().io()

    private val teams = wiring.routes?.let { routes ->
        TeamsRoutes(
            TeamSource { ports.teams },
            wiring.sessionHeads,
            sessions,
            ActivitySource { ports.activity },
            SentTextSource(routes::sentTexts),
            lookup = ProjectLookup(ProjectOf(routes.projects::of), ProjectAt { routes.projects.at(it, null) }),
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
        // the recorded listing, so a member that registered and ended is an ended row, not one that never registered
        registry = wiring.listed,
        home = UserHome.dir(),
    )

    /** End all: every started member ended as its own End would, armed by what any would lose (TeamEnd). */
    private val endAll = TeamEnd(
        TeamSource { ports.teams },
        SessionEnd(wiring.drive, RegistryHandover(sessions, ProcessWaiter()), sessions),
    )

    /** What a member's screen is offering right now, read through Sessions' own drive, launch records included. */
    private val screen = TeamScreen(TeamSource { ports.teams }, wiring.drive)

    /** There is deliberately no GET /api/teams/{id}; the board composes from these and /api/sessions. */
    fun register(route: Route) {
        teams?.let { registerTeams(route, it) }
        // End all: every started member's session ended, the team kept (TeamEnd)
        route.post("/api/teams/{id}/end") {
            guard.guarded(call) { endAll.endJson(id(call), call.receiveText()).send(call) }
        }
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
        route.post("/api/teams/{id}/restore") { guard.guarded(call) { teams.restore(id(call)).send(call) } }
        registerMembers(route)
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

    /** The three writes that drive a member: start it, stop its turn, answer what it is waiting on, and the read of
     *  what it is asking. The terminal behind all four is SessionTerminal's contract, read per call. */
    private fun registerMembers(route: Route) {
        route.post("/api/teams/{id}/slots/{slot}/start") {
            // the start waits on tmux, polls the pane and, when the member never comes up, waits up to ten seconds on
            // its exit: all blocking, so none of it holds the call's own thread
            guard.guarded(call) {
                val team = id(call)
                val member = slot(call)
                withContext(blockingIo) { starts.start(team, member) }.send(call)
            }
        }
        route.post("/api/teams/{id}/slots/{slot}/stop") {
            guard.guarded(call) { starts.stop(id(call), slot(call)).send(call) }
        }
        route.post("/api/teams/{id}/slots/{slot}/answer") {
            guard.guarded(call) {
                // the label is the words the page drew under the click, so a screen that changed is refused
                val body = call.receiveText()
                val label = JsonScalars.objectOrNull(Json, body)?.let { JsonScalars.str(it, "label") }
                starts.answer(id(call), slot(call), choice(body), label).send(call)
            }
        }
        // The choices a member is showing, as its own client drew them (ScreenChoices): the card draws Allow /
        // Always allow / Deny because they are on the screen, never because splice keeps a list of them.
        route.get("/api/teams/{id}/slots/{slot}/screen") {
            guard.guarded(call) { screen.offer(id(call), slot(call)).send(call) }
        }
    }

    private fun id(call: ApplicationCall): String = call.parameters["id"].orEmpty()

    private fun slot(call: ApplicationCall): String = call.parameters["slot"].orEmpty()

    /** The numbered choice a body names. A body that names none answers 0, which the route refuses in words. */
    private fun choice(body: String): Int {
        val asked = JsonScalars.objectOrNull(Json, body) ?: return 0
        return JsonScalars.long(asked, "choice")?.toInt() ?: 0
    }
}
