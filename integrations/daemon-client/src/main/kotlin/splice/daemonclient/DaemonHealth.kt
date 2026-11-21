// NEW: HTTP /health + TCP bind probes for the CLI cold-start path.
// Split from DaemonLaunch.kt so the launch composer is not billed as a
// god object (concentration HIGH, 2026-08-19). JW-01 boot-log tokens
// stay on DaemonLaunch.daemonLaunchArgv. In integrations/daemon-client since LAYOUT-01, beside the
// DaemonProbe it reads, so a verb outside app can ask whether the daemon is up.
package splice.daemonclient

import splice.core.GATEWAY_VERSION
import splice.daemonclient.DaemonProbe.HealthView
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/** Whether the local daemon is up (its versioned /health answers) and whether its port is still held. */
public class DaemonHealth {

    public fun healthView(port: Int): HealthView? = DaemonProbe.healthView(port)

    /** True only when the listener answers splice's versioned HTTP health contract with [expected] —
     *  this CLI's own version unless the caller just activated another (`splice upgrade`). */
    public fun daemonUp(port: Int, expected: String = GATEWAY_VERSION): Boolean =
        healthView(port)?.version == expected

    public fun cliVersion(): String = GATEWAY_VERSION

    /** True while something still holds [port] — a TCP connect succeeds (or is ambiguous: timeout/IO).
     *  False ONLY on an explicit refusal (ConnectException), i.e. the listener is actually gone. */
    public fun controlPortBound(port: Int): Boolean =
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

    /** custom-provider (cp-ready): what [port]'s listener is doing right now, one /health probe's worth: already
     *  [BoundDaemon.Up] as [expected], bound by [BoundDaemon.OtherVersion] (today's handling: wait no further),
     *  [BoundDaemon.Starting] (bound, not yet answering — a daemon beginning up looks the same as one mid-shutdown
     *  until it either answers or releases the port), or [BoundDaemon.Free]. */
    private fun boundDaemon(port: Int, expected: String): BoundDaemon =
        when (val probe = DaemonProbe.healthProbe(port)) {
            is DaemonProbe.HealthProbe.Up ->
                if (probe.view.version == expected) BoundDaemon.Up else BoundDaemon.OtherVersion
            DaemonProbe.HealthProbe.Down -> BoundDaemon.Free
            is DaemonProbe.HealthProbe.Slow, is DaemonProbe.HealthProbe.Odd -> BoundDaemon.Starting
        }

    /** custom-provider (cp-ready): a bound port may hold a daemon that is up but has not yet answered its first
     *  /health (found cutting custom-provider, Nov 18, 2026) rather than one stuck mid-shutdown; the two look
     *  identical from one probe. Polls up to [polls] times, [intervalMs] apart — the same cold-start budget a
     *  daemon this CLI spawned itself gets — so a slow first answer is waited out instead of refused. Stops the
     *  moment the listener answers at all: [BoundDaemon.OtherVersion] is never waited on further, since no amount
     *  of waiting turns it into [expected]. */
    public fun waitForBoundDaemon(port: Int, expected: String, polls: Int, intervalMs: Long): BoundDaemon {
        repeat(polls) {
            val outcome = boundDaemon(port, expected)
            if (outcome != BoundDaemon.Starting) return outcome
            Thread.sleep(intervalMs)
        }
        return boundDaemon(port, expected)
    }
}

/** custom-provider (cp-ready): see [DaemonHealth.waitForBoundDaemon]. */
public enum class BoundDaemon { Up, OtherVersion, Starting, Free }

private const val PROBE_TIMEOUT_MS = 400
