// NEW: LAYOUT-01 — the sessions capability's routes: the Claude Code session registry, its edges and
// transcripts, and the team and project reads over the same registry (features/sessions).
package splice.app.control.mount

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.SessionHeadAdapter
import splice.client.transcript.TranscriptReader
import splice.core.config.ConfigService
import splice.sessions.http.ActivitySource
import splice.sessions.http.CompactionSource
import splice.sessions.http.ProjectsRoutes
import splice.sessions.http.RepoOf
import splice.sessions.http.SentTextSource
import splice.sessions.http.SessionsRoutes
import splice.sessions.http.StatuslineRootOf
import splice.sessions.http.TeamSource
import splice.sessions.http.TeamsRoutes
import splice.sessions.registry.SessionSource

/** Every route here is registered only when a session registry is wired, as /api/sessions always was.
 *  [ports] is read at CALL time: ControlPlane assigns the activity and team stores after construction. */
internal class SessionsMount(
    sessions: SessionSource?,
    heads: Map<String, ManagedHead>,
    config: ConfigService,
    ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val sessionHeads = SessionHeadAdapter.adapt(heads)

    private val sessionsRoutes = sessions?.let {
        SessionsRoutes(
            it,
            TranscriptReader(),
            sessionHeads,
            config,
            ActivitySource { ports.activity },
            teams = TeamSource { ports.teams },
        )
    }
    private val teamsRoutes = sessionsRoutes?.let { routes ->
        TeamsRoutes(
            TeamSource { ports.teams },
            sessionHeads,
            sessions,
            ActivitySource { ports.activity },
            SentTextSource(routes::sentTexts),
        )
    }
    private val projectsRoutes = sessionsRoutes?.let { routes ->
        ProjectsRoutes(
            sessions,
            sessionHeads,
            RepoOf(routes::repoOf),
            TeamSource { ports.teams },
            statuslineRoot = StatuslineRootOf(routes::statuslineRootOf),
            compaction = CompactionSource { ports.compaction },
        )
    }

    fun register(route: Route) {
        sessionsRoutes?.let { sessionRoutes(route, it) }
    }

    /** v0.4.0 /api/sessions, V4-130's three session reads beside it, and V4-131's team and project
     *  routes, which read the same registry. */
    private fun sessionRoutes(route: Route, routes: SessionsRoutes) {
        route.get("/api/sessions") { guard.guarded(call) { ControlReplies.respond(call, routes.sessionsJson()) } }
        route.get("/api/sessions/edges") { guard.guarded(call) { routes.edgeRoutes.boardEdges().send(call) } }
        route.get("/api/sessions/{id}/edges") {
            guard.guarded(call) { routes.edgeRoutes.edges(call.parameters["id"].orEmpty()).send(call) }
        }
        route.get("/api/sessions/{id}/transcript") {
            guard.guarded(call) {
                val id = call.parameters["id"].orEmpty()
                val query = call.request.queryParameters
                routes.transcript(id, query["cursor"], query["limit"]?.toIntOrNull()).send(call)
            }
        }
        teamsRoutes?.let { teamRoutes(route, it) }
        projectsRoutes?.let { projects ->
            route.get("/api/projects") { guard.guarded(call) { projects.list().send(call) } }
            route.get("/api/projects/{id}") { guard.guarded(call) { projects.project(id(call)).send(call) } }
            route.get("/api/projects/{id}/files") { guard.guarded(call) { projects.files(id(call)).send(call) } }
        }
    }

    /** V4-131 (FEATURES.md 6.1): the SPLIT team reads and the team writes. There is deliberately no
     *  GET /api/teams/{id}; the board composes from these and /api/sessions. */
    private fun teamRoutes(route: Route, teams: TeamsRoutes) {
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
        route.get("/api/teams/{id}/chat") {
            guard.guarded(call) { teams.reads.chat(id(call), call.request.queryParameters["day"]).send(call) }
        }
        route.get("/api/teams/{id}/activity") {
            guard.guarded(call) { teams.reads.activity(id(call), call.request.queryParameters["day"]).send(call) }
        }
        route.get("/api/teams/{id}/economics") { guard.guarded(call) { teams.economics(id(call)).send(call) } }
    }

    private fun id(call: ApplicationCall): String = call.parameters["id"].orEmpty()
}
