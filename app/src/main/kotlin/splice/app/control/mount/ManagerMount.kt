// NEW: Oct 15, 2026 (the splice manager) — the composer's three rows: the manager's state, its start and its head
// (ManagerSession). The machinery is Sessions' and Teams' own: the one drive every pane is reached through, the head
// commands a team member starts under, the registry's arrival, and Continue on over the listing Sessions draws.
package splice.app.control.mount

import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.ManagedHead
import splice.app.control.api.HeadResolver
import splice.app.manager.ManagerHome
import splice.client.CLAUDE_PROJECTS_DIRECTORY
import splice.client.resume.TRANSCRIPT_SUFFIX
import splice.core.config.ConfigService
import splice.core.config.UserHome
import splice.core.session.SessionLines
import splice.sessions.http.SessionContinue
import splice.sessions.http.TranscriptRoots
import splice.sessions.manager.ManagerDrive
import splice.sessions.manager.ManagerPointerStore
import splice.sessions.manager.ManagerSession
import splice.sessions.manager.ManagerTranscripts
import splice.sessions.registry.SessionSource
import splice.topology.TopologyLoader
import splice.upstream.codemode.ProcessWaiter

internal class ManagerMount(
    wiring: SessionsWiring,
    sessions: SessionSource?,
    heads: Map<String, ManagedHead>,
    resolver: HeadResolver,
    config: ConfigService,
    private val guard: ControlGuard,
    lines: SessionLines,
) {
    private val commands = HeadStartCommands(heads)

    private val manager = ManagerSession(
        // the splice.toml the daemon booted from: Main resolves it the same way, from the same environment
        home = HomeManagerFolder(ManagerHome(TopologyLoader.configPath(), config.statePaths.stateDir)),
        pointers = ManagerPointerStore(config.statePaths.stateDir),
        heads = FleetHeadChoices(resolver, heads, commands),
        ports = ManagerDrive(
            drive = wiring.drive,
            commands = commands,
            arrival = RegistryArrival(sessions, ProcessWaiter()),
            registry = sessions,
            transcripts = ManagerTranscripts(
                roots = TranscriptRoots(wiring.sessionHeads),
                projectsDirectory = CLAUDE_PROJECTS_DIRECTORY,
                suffix = TRANSCRIPT_SUFFIX,
            ),
            continueOn = SessionContinue(
                drive = wiring.drive,
                commands = commands,
                handover = RegistryHandover(sessions, ProcessWaiter()),
                // the listing Sessions draws, as its own Continue on reads it
                registry = wiring.listed,
                home = UserHome.dir(),
                ledger = wiring.moveLedger,
            ),
        ),
        lines = lines,
    )

    fun register(route: Route) {
        route.get("/api/manager") { guard.guarded(call) { manager.state().send(call) } }
        route.post("/api/manager/start") { guard.guarded(call) { manager.startJson(call.receiveText()).send(call) } }
        route.post("/api/manager/head") { guard.guarded(call) { manager.headJson(call.receiveText()).send(call) } }
    }
}
