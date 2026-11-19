// The daemon a stop (`splice restart`, `splice stop`) finds on the control port, as the stop names it. A daemon too
// busy to answer /health in the probe's window is still one to stop: taken for none, the cold start after it found the
// port held, and the restart failed with the old daemon still serving.
package splice.lifecycle.restart

import splice.daemonclient.DaemonProbe

internal class DaemonToStop {
    /** How the daemon on [port] is named as it is stopped: its version, or that it is slow to answer; null when there
     *  is no splice daemon there to stop. */
    fun named(port: Int): String? = when (val probe = DaemonProbe.healthProbe(port)) {
        is DaemonProbe.HealthProbe.Up -> probe.view.version
        is DaemonProbe.HealthProbe.Slow -> "(slow to answer)"
        // Nothing listens, or what listens is no splice daemon: there is nothing of ours to stop.
        DaemonProbe.HealthProbe.Down, is DaemonProbe.HealthProbe.Odd -> null
    }
}
