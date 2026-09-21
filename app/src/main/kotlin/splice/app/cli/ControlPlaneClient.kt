// NEW: tiny HTTP client for the daemon's loopback control plane. HTTP status only —
// the fetch cluster (health / heads / auth) moved to DaemonBoundary + TopologyLoader
// (concentration, 2026-08-19). [statusOf] stays because DaemonStop needs to SEE a
// 401/403; request() swallows non-2xx and that would paper over F1.
package splice.app.cli

import splice.core.util.Cancellables
import java.net.HttpURLConnection
import java.net.URI

/** One control-plane answer: the status line and the body that came with it. */
internal data class ControlReply(val status: Int, val body: String)

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
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): declared 'null if it never connected' — a closed control port is the normal case and the escalation ladder branches on the null; the 401/403 this exists to expose arrives as a STATUS, not as a failure.
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

    /** V4-175: status AND body, for the calls whose ANSWER IS A SENTENCE. The control plane writes
     *  every refusal as `{"error": "<reason>"}` — "claude is not currently wrapped", "the
     *  'claude-splice' head is not configured — wrap needs its catalog to materialize" — and a
     *  caller that printed only the code would be hiding the one thing the operator can act on.
     *  Null when it never connected, the same contract [statusOf] carries. */
    internal fun send(
        url: String,
        method: String,
        bearer: String?,
        readTimeoutMs: Int = STATUS_TIMEOUT_MS,
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-20 (V4-175): declared 'null if it never connected', same as statusOf above; a closed control port is the normal case and the caller branches on the null.
    ): ControlReply? = Cancellables.runCatchingCancellable {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            val status = connection.responseCode
            // A non-2xx puts the body on the ERROR stream and leaves inputStream throwing, which is
            // exactly the half that carries the reason.
            val stream = if (status in OK_RANGE) connection.inputStream else connection.errorStream
            ControlReply(status, stream?.readBytes()?.decodeToString().orEmpty())
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** What both halves of a control-plane call agree a success is: the sender picks the stream to
     *  read from it, and [SetupClaudeLane] picks the sentence to print from it. Two spellings of
     *  "2xx" in two files is one rename away from a refusal being reported as a success. */
    internal val OK_RANGE = HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE

    private const val PROBE_TIMEOUT_MS = 400

    // The shutdown POST answers 202 BEFORE tearing down, but under load that answer (or a 401/403)
    // can take longer than a liveness probe's 400ms. Timing out there produced `null`, which the
    // ladder reads as an expected transport drop — silently losing the diagnostic F1 added.
    private const val STATUS_TIMEOUT_MS = 3_000
}
