// NEW: V4-444 — the projects capability's routes (V4-131): a project is a git root, read over the session
// registry, the teams and the compaction rules. Split from SessionsMount, which keeps the registry's own routes.
package splice.app.control.mount

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import splice.app.control.ConsolePorts
import splice.core.config.ConfigService
import splice.sessions.http.CompactionSource
import splice.sessions.http.ProjectAt
import splice.sessions.http.ProjectFolders
import splice.sessions.http.ProjectLookup
import splice.sessions.http.ProjectOf
import splice.sessions.http.ProjectsRoutes
import splice.sessions.http.TeamSource
import splice.sessions.registry.SessionSource

/** Registered only when a session registry is wired, as the project routes always were. [ports] is read at CALL
 *  time: ControlPlane assigns the team store and the compaction rules after construction. */
internal class ProjectsMount(
    wiring: SessionsWiring,
    sessions: SessionSource?,
    config: ConfigService,
    ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    private val projects = wiring.routes?.let { routes ->
        ProjectsRoutes(
            wiring.projectSessions(checkNotNull(sessions), config),
            wiring.sessionHeads,
            ProjectLookup(
                ProjectOf(routes.projects::of),
                ProjectAt { routes.projects.at(it, null) },
                ProjectFolders(routes.projects::folders),
            ),
            TeamSource { ports.teams },
            compaction = CompactionSource { ports.compaction },
        )
    }

    fun register(route: Route) {
        projects?.let { read ->
            route.get("/api/projects") { guard.guarded(call) { read.list().send(call) } }
            route.get("/api/projects/{id}/files") { guard.guarded(call) { read.files(id(call)).send(call) } }
        }
    }

    private fun id(call: ApplicationCall): String = call.parameters["id"].orEmpty()
}
