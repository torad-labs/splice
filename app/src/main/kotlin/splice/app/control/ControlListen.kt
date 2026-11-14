// NEW: what the control plane listens on — its port, and the already-listening socket a manager handed over for it
// when there is one. Two ControlServer parameters would be one too many: that constructor is at the width ceiling
// of 7, and the two are one fact anyway, since the port is where the server listens and the socket is how.
package splice.app.control

import splice.app.control.mount.ControlGuard
import splice.http.listen.AdoptedBootstrap

/** [adopted] null = the server binds [port] itself, which is every start without socket activation. */
internal data class ControlListen(
    val port: Int,
    val adopted: AdoptedBootstrap? = null,
) {
    /** How a hand-over leaves this listener, over the admission [guard] that tracks its connections; null for a port
     *  the server bound itself, which has nothing to hand on. */
    fun handOn(guard: ControlGuard): ControlHandOn? = adopted?.let { ControlHandOn(it, guard) }
}

/** #525: a control listener a socket manager holds, as a hand-over leaves it. No new connection is taken and the ones
 *  with no request on them are closed, so a request sent from then on waits in the kernel for the next daemon, the
 *  way a head's does (HeadEngine.closeAdmission). The engine behind it keeps answering the calls it has until its own
 *  stop. */
internal class ControlHandOn(private val socket: AdoptedBootstrap, private val guard: ControlGuard) {
    fun leave() {
        socket.pauseAccepting()
        guard.closeIdleConnections()
    }
}
