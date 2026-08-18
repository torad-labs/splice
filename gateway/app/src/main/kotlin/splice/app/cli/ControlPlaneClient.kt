// NEW: tiny HTTP client for the daemon's loopback control plane, used by the operator CLI
// (restart, doctor). Split from AdminSupport purely for size — same idiom, same timeouts.
// HTTP ONLY: the stop ladder that used to live here shells out to `ss`, matches cmdlines and sends
// POSIX signals, which is a process lifecycle and not a transport — it moved to DaemonStop.kt, the
// symmetric counterpart of DaemonLaunch. [statusOf] stays because it is a request block like every
// other one here; DaemonStop is its only caller.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import java.net.HttpURLConnection
import java.net.URI

internal object ControlPlaneClient {
    private val json = Json { ignoreUnknownKeys = true }

    /** JW-02: what /health actually says — the version AND the head counters the launch shim
     *  already waits on. Doctor reads the counters; restart keeps the thin version accessor. */
    data class HealthView(
        val version: String?,
        val heads: Int?,
        val readyHeads: Int?,
        val failedHeads: Int?,
        // JW-04: the booted config identity + the daemon's own per-request staleness recompute.
        val topologyDigest: String? = null,
        val configPath: String? = null,
        val topologyStale: Boolean? = null,
        // 2026-08-12: the turn-path liveness verdict. WITHOUT these, doctor reads only the head
        // COUNTERS — which is exactly what made the 91h wedge invisible: every head was configured
        // and "ready" while no turn could complete, so doctor would have reported "4 of 4 head(s)
        // ready" straight through a total outage. Nullable because a pre-probe daemon omits them.
        val ok: Boolean? = null,
        val turnPathStalled: List<String> = emptyList(),
    )

    /** The /health payload of any splice-shaped listener, or null when nothing answers.
     *  Unlike AdminSupport.daemonUp this accepts a STALE daemon — restart must be able to stop one.
     *  str() (JsonNull-filtering) keeps a foreign listener's {"version": null} from reading back as
     *  the literal string "null". */
    fun healthView(port: Int): HealthView? = Cancellables.runCatchingCancellable {
        request("http://127.0.0.1:$port/health") { connection ->
            val obj = json.parseToJsonElement(body(connection)).jsonObject
            HealthView(
                version = JsonScalars.str(obj, "version"),
                heads = JsonScalars.int(obj, "heads"),
                readyHeads = JsonScalars.int(obj, "readyHeads"),
                failedHeads = JsonScalars.int(obj, "failedHeads"),
                topologyDigest = JsonScalars.str(obj, "topologyDigest"),
                configPath = JsonScalars.str(obj, "configPath"),
                topologyStale = (obj["topologyStale"] as? JsonPrimitive)?.booleanOrNull,
                ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull,
                turnPathStalled = (obj["turnPathStalled"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),
            )
        }
    }.getOrNull()

    fun healthVersion(port: Int): String? = healthView(port)?.version

    /** JW-05: the per-head runtime counters from /api/heads (bearer-guarded) — the
     *  local-origin vs provider-error split G20 built for exactly this diagnosis. */
    data class HeadRuntime(val key: String, val localOriginErrors: Long, val providerErrors: Long)

    fun headsRuntime(port: Int, bearer: String): List<HeadRuntime>? = Cancellables.runCatchingCancellable {
        request("http://127.0.0.1:$port/api/heads", bearer = bearer) { connection ->
            val obj = json.parseToJsonElement(body(connection)).jsonObject
            (obj["heads"] as? JsonArray).orEmpty().mapNotNull { el ->
                val head = el as? JsonObject ?: return@mapNotNull null
                val health = head["health"] as? JsonObject ?: return@mapNotNull null
                HeadRuntime(
                    key = JsonScalars.str(head, "key") ?: return@mapNotNull null,
                    localOriginErrors = JsonScalars.long(health, "localOriginErrors") ?: 0L,
                    providerErrors = JsonScalars.long(health, "providerErrors") ?: 0L,
                )
            }
        }
    }.getOrNull()

    /** The head ports the RUNNING daemon actually holds, or null when /api/heads is unreachable.
     *
     *  The running daemon — not splice.toml — is authoritative for what is BOUND, and the two
     *  disagree in exactly the case `splice restart` exists to serve: an operator edits the file
     *  (including changing a head's port) and restarts to pick it up. Reading ports from the edited
     *  file would then check the NEW port while the old daemon still holds the OLD one, so the stop
     *  reports success against a port nothing ever bound. It also survives a malformed TOML, which
     *  no file-sourced list can. */
    fun headPorts(port: Int, bearer: String): List<Int>? = Cancellables.runCatchingCancellable {
        request("http://127.0.0.1:$port/api/heads", bearer = bearer) { connection ->
            val obj = json.parseToJsonElement(body(connection)).jsonObject
            (obj["heads"] as? JsonArray).orEmpty().mapNotNull { JsonScalars.int(it as? JsonObject, "port") }
        }
    }.getOrNull()

    /** Per-head credential presence as the DAEMON sees it (`/api/auth`), or null when unreachable.
     *  Doctor compares this against shell-side presence to catch the exported-after-boot trap. */
    fun authPresence(port: Int, key: String): Map<String, Boolean>? = Cancellables.runCatchingCancellable {
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
        read: ResponseRead<T>,
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

    /** The raw HTTP status of a request, or null if it never connected. Unlike [request] this does
     *  NOT swallow non-2xx — DaemonStop.stopDaemon needs to SEE a 401/403, since that names the root
     *  cause (mgmt-key mismatch) the escalation ladder would otherwise silently paper over (F1). */
    /** [readTimeoutMs] defaults to the shutdown budget, NOT PROBE_TIMEOUT_MS: 400ms is a liveness
     *  probe's budget, and a busy daemon that takes longer than that to answer 401/403 would time
     *  out into `null` — read by the caller as "transport drop, expected", so F1's whole point (make
     *  the rejection VISIBLE) would silently not happen in exactly the loaded case it matters. */
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

    private fun body(connection: HttpURLConnection): String =
        connection.inputStream.bufferedReader().use { it.readText() }

    private const val PROBE_TIMEOUT_MS = 400

    // The shutdown POST answers 202 BEFORE tearing down, but under load that answer (or a 401/403)
    // can take longer than a liveness probe's 400ms. Timing out there produced `null`, which the
    // ladder reads as an expected transport drop — silently losing the diagnostic F1 added.
    private const val STATUS_TIMEOUT_MS = 3_000
}
