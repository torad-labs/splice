// NEW: tiny HTTP client for the daemon's loopback control plane. HTTP status only —
// the fetch cluster (health / heads / auth) moved to DaemonBoundary + TopologyLoader
// (concentration, 2026-08-19). [statusOf] stays because DaemonStop needs to SEE a
// 401/403; request() swallows non-2xx and that would paper over F1.
package splice.app.cli

import splice.core.util.Cancellables
import java.net.HttpURLConnection
import java.net.URI

internal object ControlPlaneClient {

    /** The raw HTTP status of a request, or null if it never connected. Unlike the 2xx-gated
     *  fetch helper this does NOT swallow non-2xx — DaemonStop.stopDaemon needs to SEE a 401/403,
     *  since that names the root cause (mgmt-key mismatch) the escalation ladder would otherwise
     *  silently paper over (F1). */
    /** [readTimeoutMs] defaults to the shutdown budget, NOT the liveness-probe 400ms: a busy
     *  daemon that takes longer than that to answer 401/403 would time out into `null` — read
     *  by the caller as "transport drop, expected", so F1's whole point (make the rejection
     *  VISIBLE) would silently not happen in exactly the loaded case it matters. */
    internal fun statusOf(
        url: String,
        method: String,
        bearer: String?,
        readTimeoutMs: Int = STATUS_TIMEOUT_MS,
    ): Int? = Cancellables.runCatchingCancellable {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private const val PROBE_TIMEOUT_MS = 400

    // The shutdown POST answers 202 BEFORE tearing down, but under load that answer (or a 401/403)
    // can take longer than a liveness probe's 400ms. Timing out there produced `null`, which the
    // ladder reads as an expected transport drop — silently losing the diagnostic F1 added.
    private const val STATUS_TIMEOUT_MS = 3_000
}
