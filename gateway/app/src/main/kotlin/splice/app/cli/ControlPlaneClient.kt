// NEW: tiny HTTP client for the daemon's loopback control plane, used by the operator CLI
// (restart, doctor). Split from AdminSupport purely for size — same idiom, same timeouts.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.util.discard
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import java.net.HttpURLConnection
import java.net.URI

internal object ControlPlaneClient {
    private val json = Json { ignoreUnknownKeys = true }

    /** The version any splice-shaped listener reports on /health, or null when nothing answers.
     *  Unlike AdminSupport.daemonUp this accepts a STALE daemon — restart must be able to stop one.
     *  str() (JsonNull-filtering) keeps a foreign listener's {"version": null} from reading back as
     *  the literal string "null". */
    fun healthVersion(port: Int): String? = runCatchingCancellable {
        request("http://127.0.0.1:$port/health") { connection ->
            json.parseToJsonElement(body(connection)).jsonObject.str("version")
        }
    }.getOrNull()

    /** Ask the daemon to shut down (bearer-guarded) and wait until the port stops answering.
     *  The POST is fire-and-observe: a graceful teardown can drop the connection mid-response
     *  (read-timeout) before it 2xx's, so the POST outcome is NOT the signal — the health poll is.
     *  Failure is reported only when the daemon is still answering after the whole poll budget. */
    fun stopDaemon(port: Int, key: String): Boolean {
        runCatchingCancellable {
            request("http://127.0.0.1:$port/api/daemon/shutdown", method = "POST", bearer = key) { true }
        }.discard("POST may fail on graceful teardown; the health poll below is the real stop signal")
        repeat(STOP_POLLS) {
            if (healthVersion(port) == null) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return healthVersion(port) == null
    }

    /** Per-head credential presence as the DAEMON sees it (`/api/auth`), or null when unreachable.
     *  Doctor compares this against shell-side presence to catch the exported-after-boot trap. */
    fun authPresence(port: Int, key: String): Map<String, Boolean>? = runCatchingCancellable {
        request("http://127.0.0.1:$port/api/auth", bearer = key) { connection ->
            json.parseToJsonElement(body(connection)).jsonObject.mapValues { (_, v) ->
                v.jsonObject["present"]?.jsonPrimitive?.booleanOrNull == true
            }
        }
    }.getOrNull()

    private fun <T> request(
        url: String,
        method: String = "GET",
        bearer: String? = null,
        read: (HttpURLConnection) -> T,
    ): T? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            // 2xx (the shutdown endpoint answers 202 Accepted); anything else is a miss.
            val ok = connection.responseCode in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE
            if (ok) read(connection) else null
        } finally {
            connection.disconnect()
        }
    }

    private fun body(connection: HttpURLConnection): String =
        connection.inputStream.bufferedReader().use { it.readText() }

    private const val PROBE_TIMEOUT_MS = 400
    private const val STOP_POLLS = 60
    private const val POLL_INTERVAL_MS = 250L
}
