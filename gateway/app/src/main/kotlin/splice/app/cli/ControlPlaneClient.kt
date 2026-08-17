// NEW: tiny HTTP client for the daemon's loopback control plane, used by the operator CLI
// (restart, doctor). Split from AdminSupport purely for size — same idiom, same timeouts.
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

    /** Ask the daemon to shut down (bearer-guarded) and wait until the LISTENER is actually gone.
     *  The POST is fire-and-observe: a graceful teardown can drop the connection mid-response
     *  (read-timeout) before it 2xx's, so the POST outcome is NOT the signal — the stop poll is.
     *  Failure is reported only when the port is still bound after the whole poll budget. */
    fun stopDaemon(port: Int, key: String, headPorts: List<Int> = emptyList()): Boolean {
        // F1: SEE the shutdown status — a 401/403 names the root cause (mgmt-key mismatch), which
        // the old fire-and-forget silently swallowed, then escalated as if the daemon were merely
        // slow (observed twice on 2026-08-11). statusOf does not gate on 2xx the way request() does.
        when (val status = statusOf("http://127.0.0.1:$port/api/daemon/shutdown", "POST", key)) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> println(
                "splice: shutdown request REJECTED — the mgmt key on disk does not match the " +
                    "running daemon's. Escalating to OS signals (scoped to the daemon on :$port).",
            )
            null -> Unit // transport drop on graceful teardown is expected; the poll decides
            in HTTP_OK..HTTP_LAST_SUCCESS -> Unit // 202 Accepted: the daemon is stopping cooperatively
            // Everything else — 404 from a daemon predating the endpoint, 500, 503 — used to fall
            // into the same `else` as 202 and be read as a cooperative stop, so the CLI sat out the
            // whole graceful rung waiting on a request the daemon never honoured.
            else -> println("splice: shutdown returned HTTP $status — not an accepted stop; escalating.")
        }

        // Escalation ladder. Each rung advances only while a port is still bound (release is the
        // sole success signal — BS-4), and every kill is SCOPED to the process actually holding the
        // TARGET control port (F2): a bare cmdline match would SIGKILL every splice daemon on the
        // box, production and a mid-run oracle daemon included. SIGTERM engages the daemon's own
        // 8s cooperative stop + 10s halt(0) floor, so the SIGTERM rung waits past that floor.
        if (pollStopped(port, headPorts, GRACEFUL_POLLS)) return true
        escalate(port, "SIGTERM", "ignored the shutdown request") { it.destroy() }
        if (pollStopped(port, headPorts, SIGTERM_POLLS)) return true
        escalate(port, "SIGKILL", "survived SIGTERM past the halt floor") { it.destroyForcibly() }
        return pollStopped(port, headPorts, SIGKILL_POLLS)
    }

    /** Send one rung's signal, and SAY SO when it could not be sent.
     *
     *  Both rungs used `daemonOnPort(port)?.let { … }`, so every not-found case — `ss` missing from
     *  PATH (pidsOnPort's IOException becomes an empty list), an unreadable commandLine, or a daemon
     *  launched in a shape the cmdline predicate does not match (`./gradlew run`, a wrapper, a
     *  versioned jar) — skipped the signal in total silence. The "escalation ladder" then degraded
     *  to plain polling and the operator was told only "the daemon did not stop", with no hint that
     *  nothing was ever signalled. destroy()/destroyForcibly() also RETURN whether the signal was
     *  delivered, and both returns were discarded while the preceding println already claimed it
     *  had been sent. */
    private fun escalate(port: Int, signal: String, why: String, send: SignalSend) {
        val handle = daemonOnPort(port)
        if (handle == null) {
            println(
                "splice: could not identify the process holding :$port — cannot send $signal " +
                    "(is `ss` on PATH? was the daemon started from a non-standard jar?)",
            )
            return
        }
        println("splice: daemon pid ${handle.pid()} on :$port $why — $signal")
        if (!send(handle)) {
            println("splice: $signal to pid ${handle.pid()} was REFUSED (not permitted / already gone)")
        }
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

    private fun pidsOnPort(port: Int): List<Long> = Cancellables.runCatchingCancellable {
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
     *  NOT swallow non-2xx — stopDaemon needs to SEE a 401/403, since that names the root cause
     *  (mgmt-key mismatch) the escalation ladder would otherwise silently paper over (F1). */
    /** [readTimeoutMs] defaults to the shutdown budget, NOT PROBE_TIMEOUT_MS: 400ms is a liveness
     *  probe's budget, and a busy daemon that takes longer than that to answer 401/403 would time
     *  out into `null` — read by the caller as "transport drop, expected", so F1's whole point (make
     *  the rejection VISIBLE) would silently not happen in exactly the loaded case it matters. */
    private fun statusOf(
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

    // 11s: the daemon's cooperative cap is STOP_DEADLINE_MS (8s) and its halt(0) floor sits at
    // STOP_DEADLINE_MS + TEARDOWN_TAIL_GRACE_MS (10s). At 32 polls this rung expired at EXACTLY 8s,
    // so a daemon using its full budget was SIGTERM'd mid drain()/lock.close() tail — re-entering
    // shutdown() and arming a second watchdog that can halt the very drain it was waiting on.
    // Waiting past the floor means the cooperative path wins whenever it is going to win at all.
    private const val GRACEFUL_POLLS = 44
    private const val SIGTERM_POLLS = 48 // 12s: past the 10s halt(0) floor the SIGTERM hook guarantees
    private const val SIGKILL_POLLS = 12 // 3s: kernel teardown + port release
    private const val HTTP_OK = 200
    private const val HTTP_LAST_SUCCESS = 299
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val POLL_INTERVAL_MS = 250L
}
