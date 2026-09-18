// NEW: V4-156 — the console's post-construction ports, moved out of ControlPlane.start(). They
// carried ControlPlane's only imports of splice.app.cli and splice.app.console, and ControlPlane was
// band HIGH on concerns (distinct subsystems imported); the lines are unchanged, only their home is.
// ControlPlane calls [ConsoleWiring.wire] immediately after constructing the ControlServer, where the
// lines used to sit. The V4-127/V4-137 wiring pins moved with them (ConsoleWiringPinTest), and a new
// pin there asserts that ControlPlane still makes this call, since deleting the call would unwire
// all four ports at once without breaking the build.
package splice.app

import splice.app.cli.DoctorCommand
import splice.app.console.ConsoleUpgradeStatus
import splice.app.console.DrainingRestartAdapter
import splice.control.ControlServer
import splice.control.DoctorReport
import splice.control.UpgradeStatus

internal object ConsoleWiring {
    internal fun wire(srv: ControlServer, topology: BootedTopology) {
        // V4-127: the console's three read ports, assigned after construction because a constructor
        // parameter would widen ControlServer past the width ratchet — so the compiler cannot check
        // any of these lines, and deleting one does not break the build. Each route then answers its
        // NAMED 5xx, and the upgrade one is the worst of them: an unwired port there reads as a
        // measured payload saying nothing is newer, which tells an operator they are up to date when
        // nobody has ever looked. The deletion pins in ConsoleWiringPinTest are what make that a red
        // instead of a quiet lie.
        srv.declaredHeads = topology.declaredHeads
        srv.doctor = DoctorReport(DoctorCommand()::reportJson)
        srv.upgrade = UpgradeStatus(ConsoleUpgradeStatus()::json)
        // V4-137: the draining restart's supervision probe. Unlike the three above, leaving this one
        // unassigned is SAFE BY CONSTRUCTION — ControlServer.supervised is null until set and the
        // route refuses on null, so an unwired port declines to drain rather than draining a daemon
        // nothing would restart. It is assigned here anyway because the refusal is not the answer we
        // want on a host where systemd does run the daemon, and pinned for the same reason the others
        // are: the compiler cannot see this line either.
        srv.supervised = DrainingRestartAdapter()
    }
}
