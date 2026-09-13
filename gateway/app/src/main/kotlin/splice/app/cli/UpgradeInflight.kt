// NEW (v0.4.0, FEATURES.md §5): the daemon's in-flight count as `splice upgrade` reads it — the sum
// of every head's gate.inflight on /api/heads, with the mgmt key. Split from UpgradeDaemon.kt
// (concentration, 2026-09-13).
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val PROBE_TIMEOUT_S = 5L

/** Turns in flight across every head, or null when no daemon answers (nothing to wait for). */
internal fun interface UpgradeInflight {
    operator fun invoke(): Int?
}

internal class JdkUpgradeInflight(private val env: EnvReader) : UpgradeInflight {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newHttpClient()

    override fun invoke(): Int? {
        val key = (AdminSupport.readMgmtKey(env) as? MgmtKeyRead.Present)?.key ?: return null
        val port = AdminSupport.controlPort(env)
        return Cancellables.runCatchingCancellable {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/heads"))
                .timeout(Duration.ofSeconds(PROBE_TIMEOUT_S))
                .header("Authorization", "Bearer $key")
                .GET()
                .build()
            val reply = client.send(request, HttpResponse.BodyHandlers.ofString())
            json.parseToJsonElement(reply.body()).jsonObject.getValue("heads").jsonArray.sumOf { head ->
                head.jsonObject["gate"]?.jsonObject?.get("inflight")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            }
        }.getOrNull()
    }
}
