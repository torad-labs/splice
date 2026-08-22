// NEW: HTTP /health + TCP bind probes for the CLI cold-start path.
// Split from DaemonLaunch.kt so the launch composer is not billed as a
// god object (concentration HIGH, 2026-08-19). JW-01 boot-log tokens
// stay on DaemonLaunch.daemonLaunchArgv.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.GATEWAY_VERSION
import splice.core.util.Cancellables
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

internal class DaemonHealth {

    private val json = Json { ignoreUnknownKeys = true }

    /** True only when the listener answers splice's versioned HTTP health contract. */
    internal fun daemonUp(port: Int): Boolean = Cancellables.runCatchingCancellable {
        val connection = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatchingCancellable false
            val health = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = json.parseToJsonElement(health).jsonObject
            // "Up" is the control server answering with the matching version — NOT health `ok`.
            // Since 2026-08-12 `ok` means "a turn can complete", so a stalled head flips it false
            // while the daemon is very much alive; reading `ok` here made `splice status` report a
            // running daemon as "stopped" and ensureDaemon loop on a bound port (F4/F10).
            obj["version"]?.jsonPrimitive?.content == GATEWAY_VERSION
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    /** True while something still holds [port] — a TCP connect succeeds (or is ambiguous: timeout/IO).
     *  False ONLY on an explicit refusal (ConnectException), i.e. the listener is actually gone. */
    internal fun cliVersion(): String = GATEWAY_VERSION

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
