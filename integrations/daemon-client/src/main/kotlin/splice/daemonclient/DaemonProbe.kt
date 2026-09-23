// NEW: HTTP probes of a running splice daemon (/health, /api/heads, /api/auth), moved from app's
// DaemonLock.kt (LAYOUT-01). The single-flight lock stays in app; the probe is the client both the
// lock loser and the CLI verbs read the daemon through.
package splice.daemonclient

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import java.net.HttpURLConnection
import java.net.URI

/**
 * HTTP probes of a running daemon — the lock loser health-checks the winner, and doctor/restart
 * read the same /health and /api surfaces. It left app's DaemonLock.kt for integrations/daemon-client
 * (LAYOUT-01) because both sides of that sentence read it: the lock loser in app and every CLI verb
 * that asks the running daemon anything. Nested payload types so they are not a column-0 type bill
 * (concentration, 2026-08-19). Doctor keeps `HealthView` via a typealias in DoctorCheckTypes.
 */
public object DaemonProbe {

    private val json = Json { ignoreUnknownKeys = true }

    /** JW-02: what /health actually says — the version AND the head counters the launch shim
     *  already waits on. */
    public data class HealthView(
        public val version: String?,
        public val heads: Int?,
        public val readyHeads: Int?,
        public val failedHeads: Int?,
        public val topologyDigest: String? = null,
        public val configPath: String? = null,
        public val topologyStale: Boolean? = null,
        public val ok: Boolean? = null,
        public val turnPathStalled: List<String> = emptyList(),
        public val clientVersionWarning: String? = null,
    )

    /** JW-05: the per-head runtime counters from /api/heads (bearer-guarded) — the
     *  local-origin vs provider-error split G20 built for exactly this diagnosis. */
    public data class HeadRuntime(
        public val key: String,
        public val localOriginErrors: Long,
        public val providerErrors: Long,
    )

    internal fun interface ResponseRead<T> {
        operator fun invoke(connection: HttpURLConnection): T
    }

    /** The /health payload of any splice-shaped listener, or null when nothing answers.
     *  Unlike AdminSupport.daemonUp this accepts a STALE daemon — restart must be able to stop one.
     *  str() (JsonNull-filtering) keeps a foreign listener's {"version": null} from reading back as
     *  the literal string "null". */
    public fun healthView(port: Int): HealthView? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): no listener on the port is the NORMAL case for every caller — doctor and restart probe a daemon that may not be running — so the declared HealthView? absence IS the answer; this CLI-side probe holds no log lane to route a failure into.
        Cancellables.runCatchingCancellable {
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
                        ?.mapNotNull { JsonScalars.str(it) }
                        .orEmpty(),
                    clientVersionWarning = JsonScalars.str(obj, "clientVersionWarning"),
                )
            }
        }.getOrNull()

    public fun healthVersion(port: Int): String? = healthView(port)?.version

    // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): same probe contract as healthView: /api/heads unreachable is the normal no-daemon reading, and null is what the caller renders.
    public fun headsRuntime(port: Int, bearer: String): List<HeadRuntime>? = Cancellables.runCatchingCancellable {
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
    public fun headPorts(port: Int, bearer: String): List<Int>? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): declared 'null when /api/heads is unreachable'; the stop ladder branches on that null and falls back to the topology's ports.
        Cancellables.runCatchingCancellable {
            request("http://127.0.0.1:$port/api/heads", bearer = bearer) { connection ->
                val obj = json.parseToJsonElement(body(connection)).jsonObject
                (obj["heads"] as? JsonArray).orEmpty().mapNotNull { JsonScalars.int(it as? JsonObject, "port") }
            }
        }.getOrNull()

    /** Per-head credential presence as the DAEMON sees it (`/api/auth`), or null when unreachable.
     *  Doctor compares this against shell-side presence to catch the exported-after-boot trap. */
    public fun authPresence(port: Int, key: String): Map<String, Boolean>? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): declared 'null when unreachable'; doctor prints 'the daemon did not answer' for the null instead of a per-head verdict.
        Cancellables.runCatchingCancellable {
            request("http://127.0.0.1:$port/api/auth", bearer = key) { connection ->
                json.parseToJsonElement(body(connection)).jsonObject.mapValues { (_, v) ->
                    v.jsonObject["present"]?.jsonPrimitive?.booleanOrNull == true
                }
            }
        }.getOrNull()

    internal fun <T> request(
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

    internal fun body(connection: HttpURLConnection): String =
        connection.inputStream.bufferedReader().use { it.readText() }
}

private const val PROBE_TIMEOUT_MS = 400
