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
    fun stopDaemon(port: Int, key: String, headPorts: List<Int> = emptyList()): Boolean {
        // F1: SEE the shutdown status — a 401/403 names the root cause (mgmt-key mismatch), which
        // the old fire-and-forget silently swallowed, then escalated as if the daemon were merely
        // slow (observed twice on 2026-08-11). statusOf does not gate on 2xx the way request() does.
        when (statusOf("http://127.0.0.1:$port/api/daemon/shutdown", "POST", key)) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> println(
                "splice: shutdown request REJECTED — the mgmt key on disk does not match the " +
                    "running daemon's. Escalating to OS signals (scoped to the daemon on :$port).",
            )
            null -> Unit // transport drop on graceful teardown is expected; the poll decides
            else -> Unit // 202 Accepted: the daemon is stopping cooperatively
        }

        // Escalation ladder. Each rung advances only while a port is still bound (release is the
        // sole success signal — BS-4), and every kill is SCOPED to the process actually holding the
        // TARGET control port (F2): a bare cmdline match would SIGKILL every splice daemon on the
        // box, production and a mid-run oracle daemon included. SIGTERM engages the daemon's own
        // 8s cooperative stop + 10s halt(0) floor, so the SIGTERM rung waits past that floor.
        if (pollStopped(port, headPorts, GRACEFUL_POLLS)) return true
        daemonOnPort(port)?.let {
            println("splice: daemon pid ${it.pid()} on :$port ignored the shutdown request — SIGTERM")
            it.destroy()
        }
        if (pollStopped(port, headPorts, SIGTERM_POLLS)) return true
        daemonOnPort(port)?.let {
            println("splice: daemon pid ${it.pid()} on :$port survived SIGTERM past the halt floor — SIGKILL")
            it.destroyForcibly()
        }
        return pollStopped(port, headPorts, SIGKILL_POLLS)
    }

    private fun pollStopped(port: Int, headPorts: List<Int>, polls: Int): Boolean {
        repeat(polls) {
            if (stopped(port, headPorts)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return stopped(port, headPorts)
    }

    /** The splice daemon PROCESS bound to [port], or null — identified by the port's own listener
     *  (`ss`) intersected with a splice-jar cmdline, never a bare cmdline substring. This is what
     *  keeps a restart of one daemon from signalling a concurrent oracle daemon or a second head
     *  (F2), and it mirrors the oracle harness's own port+cmdline scoping. */
    private fun daemonOnPort(port: Int): ProcessHandle? = pidsOnPort(port)
        .firstNotNullOfOrNull { pid ->
            ProcessHandle.of(pid).orElse(null)?.takeIf { ph ->
                val cmd = ph.info().commandLine().orElse("")
                cmd.contains("daemon") && (cmd.contains("splice.jar") || cmd.contains("app-all.jar"))
            }
        }

    private fun pidsOnPort(port: Int): List<Long> = runCatchingCancellable {
        ProcessBuilder("ss", "-ltnpH", "( sport = :$port )").redirectErrorStream(true).start()
            .inputStream.bufferedReader().use { it.readText() }
            .let { Regex("pid=(\\d+)").findAll(it).map { m -> m.groupValues[1].toLong() }.toList() }
    }.getOrDefault(emptyList())

    /** "Stopped" means EVERY port this daemon owned is free — the control port AND the head ports.
     *  Checking only the control port (F3) let the ladder report success while :3099 lingered on
     *  non-daemon Netty threads; the next restart then boots a head into EADDRINUSE and it lands
     *  permanently failed. A daemon whose control server quit answering can still hold its ports. */
    private fun stopped(port: Int, headPorts: List<Int>): Boolean =
        healthVersion(port) == null &&
            !AdminSupport.controlPortBound(port) &&
            headPorts.none { AdminSupport.controlPortBound(it) }

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

    /** The raw HTTP status of a request, or null if it never connected. Unlike [request] this does
     *  NOT swallow non-2xx — stopDaemon needs to SEE a 401/403, since that names the root cause
     *  (mgmt-key mismatch) the escalation ladder would otherwise silently paper over (F1). */
    private fun statusOf(url: String, method: String, bearer: String?): Int? = runCatchingCancellable {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

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
