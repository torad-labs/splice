// NEW: the control plane's session source and session driver, moved out of ControlPlane.kt by their own seam, behavior
// unchanged (concentration, Oct 13, splice-lead). One session source serves the sessions routes and the heads route's
// client pids; the driver starts and stops a team member's terminal session.
package splice.app

import splice.app.control.ManagedHead
import splice.core.config.UserHome
import splice.sessions.http.SessionDriver
import splice.sessions.registry.ArrivalJoinedSessions
import splice.sessions.registry.ForegroundTools
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.RememberedPanes
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRegistry
import splice.tmux.TmuxTerminal
import java.nio.file.Path

internal class ControlSessions(
    /** The daemon's shared environment: its state, its settings, its management key and its log sink. */
    private val daemon: DaemonEnvironment,
) {
    private val environment = ProcessEnvironment()

    /** The registry of Claude Code sessions under [home], each joined with the head its arrivals named. */
    fun source(
        home: Path,
        heads: Map<String, ManagedHead>,
        console: ConsoleEventPublisher,
        foreground: ForegroundTools,
    ): ArrivalJoinedSessions = ArrivalJoinedSessions(
        SessionRegistry(
            home.resolve(UserHome.CLAUDE_DIR).resolve("sessions"),
            RouteOfPid { pid -> environment.route(pid) { port -> headOfPort(heads, port) } },
            heard = console.sessionsHeard,
            foreground = foreground,
        ),
        console.sessionHeads,
    )

    /** Oct 10, 2026: the one session driver, so a member's start and stop reach a real terminal instead of the named
     *  503 an unwired port answers. The terminal is tmux TODAY and the port names no terminal, so the day seatd carries
     *  the six acts this line is the only one that changes. The pane record is on disk under the state dir, because a
     *  session outlives the daemon that started it: splice restarts on every install and the person's session carries
     *  on, so a record held only in memory would take the console's ability to drive exactly the sessions it
     *  started. */
    fun driver(): SessionDriver = SessionDriver(TmuxTerminal(), RememberedPanes(daemon.statePaths.stateDir))

    /** The head whose Claude Code wrapper listens on [port] — the launcher's ANTHROPIC_BASE_URL. */
    private fun headOfPort(heads: Map<String, ManagedHead>, port: Int): String? =
        heads.entries.firstOrNull { it.value.launchSpec?.gateway?.port == port }?.key
}
