// NEW: who owns the control server from the moment startup builds it. The stop used to find the server only after
// startup had published it, so a stop that arrived between the bind and that publication left a live listener behind
// and released the daemon lock under it. Adoption and close are decided under one lock, so a server is either closed by
// the stop or closed at once by its own adoption: nothing can join after the stop boundary.
package splice.app.control

import kotlinx.coroutines.CancellationException
import splice.app.DaemonBoundary
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.lifecycle.restart.ShutdownDaemon

internal class ControlOwnership(
    private val boundary: DaemonBoundary,
    private val log: LogSink,
    private val shutdownDaemon: ShutdownDaemon,
) {
    private val gate = Any()
    private var closed = false
    private var owned: ControlServer? = null

    /** Takes the control port: [server] is adopted first, then bound. A port another process holds is not a crash: the
     *  daemon says so and asks to exit, and the stop then closes what was adopted. True when the listener is up. */
    suspend fun bind(server: ControlServer, controlPort: Int): Boolean {
        adopt(server)
        val bound = boundary.runCatchingDaemonBoundary { server.start() }
            .onFailure {
                // SAFE-RENDER-EXEMPT[2026-08-31]: srv.start() bind failure — a SocketException names a port and an address, never file bytes
                log(
                    "[daemon] control plane could not bind :${LogSafe.str(controlPort.toString())} " +
                        "(${LogSafe.str(it.message.orEmpty())}); another owns it, exiting\n",
                )
                shutdownDaemon()
            }
            .isSuccess
        if (bound) log("[daemon] control plane bound :${LogSafe.str(server.listeningPort.toString())}\n")
        return bound
    }

    /** The one registration. Called when startup has built [server] and before it binds, so the stop can reach it from
     *  the moment it can hold a port. After the stop began, [server] is stopped here and startup ends cancelled. */
    private fun adopt(server: ControlServer) {
        val late = synchronized(gate) {
            if (!closed) owned = server
            closed
        }
        if (late) {
            server.stop()
            throw CancellationException("the daemon is stopping, so the control server is not started")
        }
    }

    /** The stop boundary: closes what was adopted, and refuses anything adopted after. Safe to call twice. */
    fun close() {
        val server = synchronized(gate) {
            closed = true
            owned
        }
        server?.stop()
    }
}
