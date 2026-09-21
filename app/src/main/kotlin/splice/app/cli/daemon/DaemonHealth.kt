// NEW: HTTP /health + TCP bind probes for the CLI cold-start path.
// Split from DaemonLaunch.kt so the launch composer is not billed as a
// god object (concentration HIGH, 2026-08-19). JW-01 boot-log tokens
// stay on DaemonLaunch.daemonLaunchArgv.
package splice.app.cli.daemon

import splice.app.cli.doctor.HealthView
import splice.app.daemon.DaemonProbe
import splice.core.GATEWAY_VERSION
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

internal class DaemonHealth {

    internal fun healthView(port: Int): HealthView? = DaemonProbe.healthView(port)

    /** True only when the listener answers splice's versioned HTTP health contract with [expected] —
     *  this CLI's own version unless the caller just activated another (`splice upgrade`). */
    internal fun daemonUp(port: Int, expected: String = GATEWAY_VERSION): Boolean =
        healthView(port)?.version == expected

    internal fun cliVersion(): String = GATEWAY_VERSION

    /** True while something still holds [port] — a TCP connect succeeds (or is ambiguous: timeout/IO).
     *  False ONLY on an explicit refusal (ConnectException), i.e. the listener is actually gone. */
    internal fun controlPortBound(port: Int): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS) }
            true
        } catch (_: ConnectException) {
            false
        } catch (_: IOException) {
            // A connect timeout or other transient I/O error is ambiguous — treat as still-bound so an
            // uncertain signal never green-lights a racing cold start.
            true
        }
}

private const val PROBE_TIMEOUT_MS = 400
