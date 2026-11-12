// NEW: V4-137 — the :app side of the draining restart: the probe that answers whether anything would
// bring this process back after it exits.
//
// WHY INVOCATION_ID AND NOTHING MORE. systemd sets INVOCATION_ID in the environment of every unit it
// starts — verified on the live daemon, where it matches the value `systemctl show` reports for the
// unit — so its presence is the process's own evidence that it was started by systemd rather than by
// an operator's shell. That is the necessary condition for the unit's Restart=always to apply, and it
// is the only such evidence a process can read about itself without shelling out to systemctl.
//
// WHAT THIS PROBE DOES NOT PROVE, stated rather than implied: it answers "systemd started me", not
// "the unit that started me has a restart policy". A transient unit (`systemd-run`, whose name shows
// as run-r<id>.service) also sets the variable and has no Restart=, so a daemon started that way would
// read supervised here. The domain is what the route does with a refusal — it DRAINS NOTHING — so the
// cost of that gap is a 202 on a daemon that stays down, not a cut turn: the failure is a missing
// restart, never a lost one. Reading the unit's own policy would mean parsing /proc/self/cgroup for
// the unit name and then asking systemctl, which is a subprocess on the request path and a second
// source of truth about a host fact this repo does not own.
//
// THE UNIT IS NOT OURS, and naming whose it is would be naming one host's answer to a question
// every host answers differently. splice states the REQUIREMENT and reads it back: a unit that
// starts this process and restarts it when it exits. Whatever supplies that — a distro package, a
// container runtime's restart policy, an operator's own hand-written unit — owns the file, points
// its Documentation= at itself, and is where a change to it belongs. Nothing here reads, writes or
// ships a copy of one.
package splice.app.console

import splice.core.util.EnvReader
import splice.lifecycle.restart.DaemonSupervised

/** The environment variable systemd sets on every process it starts. */
internal const val INVOCATION_ID = "INVOCATION_ID"

/** Answers whether this process was started by systemd, and therefore whether the host unit's
 *  Restart=always will bring it back after a drain.
 *
 *  [env] is injected so a test can answer the question both ways without pretending to be systemd —
 *  the rig that runs the tests was started by Gradle, not by a unit, so an uninjected read would pin
 *  whatever started the JVM and the supervised arm would be untestable. */
internal class DrainingRestartAdapter(
    private val env: EnvReader = EnvReader(System::getenv),
) : DaemonSupervised {

    override fun invoke(): Boolean = !env(INVOCATION_ID).isNullOrBlank()
}
