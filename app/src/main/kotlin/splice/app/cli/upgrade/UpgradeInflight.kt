// NEW: v0.4.0 FEATURES.md §5 — the daemon's in-flight count as `splice upgrade` reads it — the sum
// of every head's gate.inflight on /api/heads, with the mgmt key. Split from UpgradeDaemon.kt
// (concentration, 2026-09-13).
package splice.app.cli.upgrade

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.app.cli.doctor.MgmtKeyRead
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val PROBE_TIMEOUT_S = 5L
private const val CONNECT_TIMEOUT_MS = 2_000
private const val HTTP_OK = 200

/** What one read of the in-flight count established: a number, a proven absence of any daemon
 *  (nothing to wait for), or nothing at all — a missing key, a timeout, a 401, a body without every
 *  head's gate.inflight. Unknown is never zero: an upgrade that cannot see the turns waits or refuses. */
internal sealed class InflightRead {
    data class Count(val turns: Int) : InflightRead()
    data object NoDaemon : InflightRead()
    data class Unknown(val reason: String) : InflightRead()
}

internal fun interface UpgradeInflight {
    operator fun invoke(): InflightRead
}

internal class JdkUpgradeInflight(
    private val env: EnvReader,
    private val port: Int = AdminSupport.controlPort(env),
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
) : UpgradeInflight {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newHttpClient()

    override fun invoke(): InflightRead {
        absent()?.let { return it }
        val key = (AdminSupport.readMgmtKey(env) as? MgmtKeyRead.Present)?.key
            ?: return InflightRead.Unknown("the mgmt key is not readable")
        return Cancellables.runCatchingCancellable {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/heads"))
                .timeout(Duration.ofSeconds(PROBE_TIMEOUT_S))
                .header("Authorization", "Bearer $key")
                .GET()
                .build()
            val reply = client.send(request, HttpResponse.BodyHandlers.ofString())
            val status = reply.statusCode()
            if (status != HTTP_OK) InflightRead.Unknown("/api/heads answered HTTP $status") else counted(reply.body())
        }.getOrElse { InflightRead.Unknown("/api/heads did not answer") }
    }

    /** POSITIVE absence evidence only: a refused connect on the control port proves nothing listens.
     *  A timeout or any other transport failure is Unknown, and a listener that answers /api/heads
     *  badly (stale daemon, foreign process, 401) is Unknown further down — never "no daemon". */
    private fun absent(): InflightRead? = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs) }
        null
    } catch (ignored: ConnectException) {
        InflightRead.NoDaemon
    } catch (e: IOException) {
        InflightRead.Unknown("the control port did not answer (${SafeFailureText.render(e)})")
    }

    /** Every head must report gate.inflight; one that does not makes the whole read Unknown. */
    private fun counted(body: String): InflightRead {
        val heads = json.parseToJsonElement(body).jsonObject.getValue("heads").jsonArray.map { it.jsonObject }
        val counts = heads.map { head ->
            JsonScalars.str(head["gate"] as? JsonObject, "inflight")?.toIntOrNull()
        }
        val missing = heads.filterIndexed { i, _ -> counts[i] == null }
            .map { JsonScalars.str(it, "key") ?: "?" }
        return if (missing.isEmpty()) {
            InflightRead.Count(counts.sumOf { it ?: 0 })
        } else {
            InflightRead.Unknown("no in-flight count for head(s) $missing")
        }
    }
}
