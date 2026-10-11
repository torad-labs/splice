// NEW: LAYOUT-01 — the sessions capability's routes: the Claude Code session registry, its history, edges and
// transcripts (features/sessions). V4-444: the team and project routes moved to TeamsMount and ProjectsMount.
package splice.app.control.mount

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.withContext
import splice.app.control.ManagedHead
import splice.core.config.ConfigService
import splice.core.config.UserHome
import splice.sessions.http.KeptActivity
import splice.sessions.http.SessionContinue
import splice.sessions.http.SessionHistoryRoute
import splice.sessions.http.SessionRepoNameOf
import splice.sessions.http.SessionsRoutes
import splice.sessions.note.PeerNoteSocket
import splice.sessions.note.SessionNoteRoute
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessWaiter

/** Every route here is registered only when a session registry is wired, as /api/sessions always was. The team and
 *  project routes over the same registry are [TeamsMount] and [ProjectsMount]. */
internal class SessionsMount(
    sessions: SessionSource?,
    private val wiring: SessionsWiring,
    heads: Map<String, ManagedHead>,
    config: ConfigService,
    private val guard: ControlGuard,
) {
    private val fileIo = ProcessDispatchers().io()
    private val historyRoutes = sessions?.let { registry ->
        val routes = checkNotNull(wiring.routes)
        SessionHistoryRoute(
            registry,
            wiring.historyIndex,
            wiring.historyRoots,
            routes.historyRows,
            SessionTranscriptViewEnabled { config.getConfig().transcriptView },
            SessionRepoNameOf { record -> routes.repoOf(record)?.root },
        )
    }

    /** Continue on: the session ended where it runs and resumed on another command (SessionContinue). */
    private val continueOn = SessionContinue(
        drive = wiring.drive,
        commands = HeadStartCommands(heads),
        handover = RegistryHandover(sessions, ProcessWaiter()),
        // the listing Sessions draws, so a session Claude Code forgot on a clean exit still moves from its Ended card
        registry = wiring.listed,
        home = UserHome.dir(),
    )

    /** V4-444: the console's note to one live session, written to its inbox socket. */
    private val noteRoute = sessions?.let { SessionNoteRoute(it, PeerNoteSocket(fileIo)) }

    fun register(route: Route) {
        wiring.routes?.let { sessionRoutes(route, it) }
    }

    /** v0.4.0 /api/sessions and V4-130's three session reads beside it. */
    private fun sessionRoutes(route: Route, routes: SessionsRoutes) {
        route.get("/api/sessions") { guard.guarded(call) { ControlReplies.respond(call, routes.sessionsJson()) } }
        historyRoutes?.let { history ->
            route.get("/api/sessions/history") {
                guard.guarded(call) {
                    val query = call.request.queryParameters
                    history.page(query["query"], query["cursor"], query["limit"]?.toIntOrNull()).send(call)
                }
            }
        }
        route.get("/api/kept/edges") {
            guard.guarded(call) { withContext(fileIo) { routes.edgeRoutes.kept(KeptActivity.EDGES) }.send(call) }
        }
        route.delete("/api/kept/edges") {
            guard.guarded(call) { withContext(fileIo) { routes.edgeRoutes.deleteKept(KeptActivity.EDGES) }.send(call) }
        }
        route.get("/api/kept/labels") {
            guard.guarded(call) { withContext(fileIo) { routes.edgeRoutes.kept(KeptActivity.LABELS) }.send(call) }
        }
        route.delete("/api/kept/labels") {
            guard.guarded(call) { withContext(fileIo) { routes.edgeRoutes.deleteKept(KeptActivity.LABELS) }.send(call) }
        }
        route.get("/api/sessions/edges") { guard.guarded(call) { routes.edgeRoutes.boardEdges().send(call) } }
        route.get("/api/sessions/{id}/edges") {
            guard.guarded(call) { routes.edgeRoutes.edges(call.parameters["id"].orEmpty()).send(call) }
        }
        noteRoute?.let { notes ->
            route.post("/api/sessions/{id}/message") {
                guard.guarded(call) { notes.post(call.parameters["id"].orEmpty(), call.receiveText()).send(call) }
            }
        }
        driveRoutes(route)
        route.get("/api/sessions/{id}/transcript") {
            guard.guarded(call) {
                val id = call.parameters["id"].orEmpty()
                val query = call.request.queryParameters
                routes.transcript(id, query["cursor"], query["limit"]?.toIntOrNull(), query["before"]).send(call)
            }
        }
    }

    // Driving one session from the console: write to it, answer what it waits on, stop its turn, read its prompt.
    // Only a session splice can reach has a pane to act in; any other is refused in words (SessionDrive).
    private fun driveRoutes(route: Route) {
        val drive = wiring.drive
        route.post("/api/sessions/{id}/say") {
            guard.guarded(call) { drive.sayJson(call.parameters["id"].orEmpty(), call.receiveText()).send(call) }
        }
        route.post("/api/sessions/{id}/answer") {
            guard.guarded(call) { drive.answerJson(call.parameters["id"].orEmpty(), call.receiveText()).send(call) }
        }
        route.post("/api/sessions/{id}/stop") {
            guard.guarded(call) { drive.stop(call.parameters["id"].orEmpty()).send(call) }
        }
        route.get("/api/sessions/{id}/screen") {
            guard.guarded(call) { drive.screen(call.parameters["id"].orEmpty()).send(call) }
        }
        route.post("/api/sessions/{id}/continue") {
            guard.guarded(call) { continueOn.moveJson(call.parameters["id"].orEmpty(), call.receiveText()).send(call) }
        }
    }
}
