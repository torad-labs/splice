// NEW: tiny HTTP client for the daemon's loopback control plane, used by the operator CLI
// (restart, doctor). Split from AdminSupport purely for size — same idiom, same timeouts.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.util.discard
import splice.core.util.int
import splice.core.util.long
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import java.net.HttpURLConnection
import java.net.URI
import kotlin.streams.toList

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
    )

    /** The /health payload of any splice-shaped listener, or null when nothing answers.
     *  Unlike AdminSupport.daemonUp this accepts a STALE daemon — restart must be able to stop one.
     *  str() (JsonNull-filtering) keeps a foreign listener's {"version": null} from reading back as
     *  the literal string "null". */
    fun healthView(port: Int): HealthView? = runCatchingCancellable {
        request("http://127.0.0.1:$port/health") { connection ->
            val obj = json.parseToJsonElement(body(connection)).jsonObject
            HealthView(
                version = obj.str("version"),
                heads = obj.int("heads"),
                readyHeads = obj.int("readyHeads"),
                failedHeads = obj.int("failedHeads"),
                topologyDigest = obj.str("topologyDigest"),
                configPath = obj.str("configPath"),
                topologyStale = (obj["topologyStale"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }.getOrNull()

    fun healthVersion(port: Int): String? = healthView(port)?.version

    /** JW-05: the per-head runtime counters from /api/heads (bearer-guarded) — the
     *  local-origin vs provider-error split G20 built for exactly this diagnosis. */
    data class HeadRuntime(val key: String, val localOriginErrors: Long, val providerErrors: Long)

    fun headsRuntime(port: Int, bearer: String): List<HeadRuntime>? = runCatchingCancellable {
        request("http://127.0.0.1:$port/api/heads", bearer = bearer) { connection ->
            val obj = json.parseToJsonElement(body(connection)).jsonObject
            (obj["heads"] as? JsonArray).orEmpty().mapNotNull { el ->
                val head = el as? JsonObject ?: return@mapNotNull null
                val health = head["health"] as? JsonObject ?: return@mapNotNull null
                HeadRuntime(
                    key = head.str("key") ?: return@mapNotNull null,
                    localOriginErrors = health.long("localOriginErrors") ?: 0L,
                    providerErrors = health.long("providerErrors") ?: 0L,
                )
            }
        }
    }.getOrNull()

    /** Ask the daemon to shut down (bearer-guarded) and wait until the LISTENER is actually gone.
     *  The POST is fire-and-observe: a graceful teardown can drop the connection mid-response
     *  (read-timeout) before it 2xx's, so the POST outcome is NOT the signal — the stop poll is.
     *  Failure is reported only when the port is still bound after the whole poll budget. */
    fun stopDaemon(port: Int, key: String): Boolean {
        // The POST outcome is fire-and-observe for TRANSPORT errors (a graceful teardown drops the
        // connection mid-response) — but an AUTH failure must be surfaced, not discarded: a 401
        // here means shutdownDaemon() never ran, and the old code silently polled 15s watching a
        // daemon nobody had asked to stop, then told the operator to "terminate manually"
        // (observed twice on 2026-08-11).
        runCatchingCancellable {
            request("http://127.0.0.1:$port/api/daemon/shutdown", method = "POST", bearer = key) { conn ->
                if (conn.responseCode == HTTP_UNAUTHORIZED || conn.responseCode == HTTP_FORBIDDEN) {
                    println(
                        "splice: shutdown request REJECTED (${conn.responseCode}) — the mgmt key on disk " +
                            "does not match the running daemon's; escalating to signals",
                    )
                }
                true
            }
        }.discard("transport failure on graceful teardown is expected; the poll + ladder below decide")

        // Escalation ladder. Each rung only advances when the LISTENER is still bound — port
        // release is the sole success signal (BS-4). SIGTERM engages the daemon's own shutdown
        // hook, which carries an 8s cooperative stop and a 10s halt(0) floor, so the SIGTERM rung
        // waits past that floor before reaching for SIGKILL.
        if (pollStopped(port, GRACEFUL_POLLS)) return true
        spliceDaemons().forEach {
            println("splice: daemon pid ${it.pid()} ignored the shutdown request — sending SIGTERM")
            it.destroy()
        }
        if (pollStopped(port, SIGTERM_POLLS)) return true
        spliceDaemons().forEach {
            println("splice: daemon pid ${it.pid()} survived SIGTERM past the halt floor — SIGKILL")
            it.destroyForcibly()
        }
        return pollStopped(port, SIGKILL_POLLS)
    }

    private fun pollStopped(port: Int, polls: Int): Boolean {
        repeat(polls) {
            if (stopped(port)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return stopped(port)
    }

    /** Every live splice DAEMON process, identified by cmdline. Matches both the installed jar
     *  (splice.jar) and a dev-tree jar (app-all.jar); never anything else — the same scoping that
     *  keeps the oracle harness's self-heal away from processes it does not own. */
    private fun spliceDaemons(): List<ProcessHandle> = ProcessHandle.allProcesses()
        .filter { ph ->
            val cmd = ph.info().commandLine().orElse("")
            cmd.contains("java") && cmd.contains("daemon") &&
                (cmd.contains("splice.jar") || cmd.contains("app-all.jar"))
        }
        .toList()

    /** "Stopped" means the LISTENER is gone, not merely that /health went null: a daemon whose control
     *  server quit answering can still linger with the port bound on non-daemon Netty threads, and
     *  reporting a premature "stopped" is what invited the restart-into-a-still-bound-port race (BS-4). */
    private fun stopped(port: Int): Boolean =
        healthVersion(port) == null && !AdminSupport.controlPortBound(port)

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
    private const val GRACEFUL_POLLS = 32 // 8s: the daemon's own cooperative stop budget
    private const val SIGTERM_POLLS = 48 // 12s: past the 10s halt(0) floor the SIGTERM hook guarantees
    private const val SIGKILL_POLLS = 12 // 3s: kernel teardown + port release
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val POLL_INTERVAL_MS = 250L
}
