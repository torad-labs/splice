// NEW: the launch hooks' session read/write contracts arrive together without widening the mount.
package splice.app.control.mount

import splice.app.control.ConsolePorts
import splice.core.client.ForegroundToolActivity
import splice.core.config.StatePaths
import splice.launch.resume.SessionStarts
import splice.sessions.registry.SessionSource
import splice.sessions.teams.TeamRuleBinding

internal class LaunchSessions(val source: SessionSource?, ports: ConsolePorts, val statePaths: StatePaths) {
    val activity: ForegroundToolActivity = ForegroundToolActivity { call -> ports.foreground?.record(call) }

    /** A session's start, for Teams' binding by rule; the store is read when the hook fires, as it is wired late. */
    val starts: SessionStarts = SessionStarts { head, session, cwd ->
        ports.teams?.let { TeamRuleBinding(it, source).started(head, session, cwd) }
    }
}
