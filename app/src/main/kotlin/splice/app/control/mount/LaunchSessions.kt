// NEW: the launch hooks' session read/write contracts arrive together without widening the mount.
package splice.app.control.mount

import splice.app.control.ConsolePorts
import splice.core.client.ForegroundToolActivity
import splice.core.config.StatePaths
import splice.sessions.registry.SessionSource

internal class LaunchSessions(val source: SessionSource?, ports: ConsolePorts, val statePaths: StatePaths) {
    val activity: ForegroundToolActivity = ForegroundToolActivity { call -> ports.foreground?.record(call) }
}
