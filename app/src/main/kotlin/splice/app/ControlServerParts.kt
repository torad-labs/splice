// NEW: the two control-server parts built from the daemon's environment alone, moved out of ControlPlane.kt by their
// own seam, behavior unchanged (concentration, Oct 13, splice-lead): the guard every control request passes, and the
// cold successor a restart hands the bound control port to.
package splice.app

import splice.app.cli.AdminSupport
import splice.app.control.ControlServer
import splice.app.control.api.ControlAudit
import splice.app.control.mount.ControlGuard
import splice.app.daemon.BootedTopology
import splice.core.config.UserHome
import splice.head.admission.AdmissionErrorBody
import splice.http.ingress.HeapIngress
import splice.lifecycle.restart.DetachedDaemonSuccessor
import splice.lifecycle.restart.SuccessorInstall
import splice.upstream.memory.JvmHeap
import java.nio.file.Path

internal class ControlServerParts(
    /** The daemon's shared environment: its state, its settings, its management key and its log sink. */
    private val daemon: DaemonEnvironment,
    private val topology: BootedTopology,
) {
    private val config get() = daemon.config
    private val log get() = daemon.log

    /** The request cap is asked for at every request, not resolved here once: the console's own PATCH of
     *  maxRequestBytes reaches the control listener's guard without a restart, the same way it reaches a head. */
    fun guard(): ControlGuard = ControlGuard(
        daemon.mgmtKey,
        ControlAudit(log),
        log,
        HeapIngress(
            JvmHeap.budget,
            { config.getConfig().maxRequestBytes },
            AdmissionErrorBody,
            readIdleMs = { config.getConfig().ingressReadIdleMs },
        ),
    )

    /** Pin the environment override to the live state directory, not its parent: the wrong parent
     *  mints a second management key and strands the daemon's prior history after restart. */
    fun successorStateDir(): Path = daemon.statePaths.stateDir

    /** The cold successor reuses this daemon's jar, home, config, state, and bound control port. */
    fun wireRestartSuccessor(srv: ControlServer, controlPort: Int) {
        srv.wireRestartSuccessor(
            DetachedDaemonSuccessor(
                AdminSupport.selfJar(),
                SuccessorInstall(UserHome.dir(), topology.path, successorStateDir(), controlPort),
                daemon.statePaths.logsDir,
                log,
            ),
        )
    }
}
