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
import splice.core.auth.CredentialVerdict
import splice.core.head.HeldTurnReading
import splice.core.usage.QuotaFull
import splice.core.util.Cancellables
import splice.core.util.FormEncoding
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.daemonclient.health.DaemonAuthReading
import splice.daemonclient.health.DaemonHealthReading
import splice.daemonclient.http.DaemonConnection
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

/**
 * HTTP probes of a running daemon — the lock loser health-checks the winner, and doctor/restart
 * read the same /health and /api surfaces. It left app's DaemonLock.kt for integrations/daemon-client
 * (LAYOUT-01) because both sides of that sentence read it: the lock loser in app and every CLI verb
 * that asks the running daemon anything. Nested payload types so they are not a column-0 type bill
 * (concentration, 2026-08-19). Doctor keeps `HealthView` via a typealias in DoctorCheckTypes.
 */
public object DaemonProbe {

    internal val json = Json { ignoreUnknownKeys = true }

    /** JW-02: what /health actually says — the version AND the head counters the launch shim
     *  already waits on. */
    public data class HealthView(
        public val version: String?,
        public val ok: Boolean? = null,
        public val heads: HealthHeads,
        public val topology: HealthTopology = HealthTopology(),
        public val client: HealthClient = HealthClient(),
        public val turns: HealthTurns = HealthTurns(),
    ) {
        public val turnPathStalled: List<String> get() = turns.stalled
        public val quota: HealthQuota get() = turns.quota
        public val held: Map<String, List<HeldTurnReading>> get() = turns.held
    }

    /** Per-head current turn health: stalled paths, quota readings, and retained holds. */
    public data class HealthTurns(
        public val stalled: List<String> = emptyList(),
        public val quota: HealthQuota = HealthQuota(),
        public val held: Map<String, List<HeldTurnReading>> = emptyMap(),
    )

    /** What /health says about the Claude Code sessions the daemon serves: [versionWarning], the aggregate version
     *  drift (`clientVersionWarning`), and [mcpHub], whether their MCP servers run under the hub. Each is null when
     *  the daemon does not say. */
    public data class HealthClient(
        public val versionWarning: String? = null,
        public val mcpHub: HealthMcpHub? = null,
    )

    /** The daemon's boot decision on the MCP hub: [on], or off with [offReason], in the words its hub routes'
     *  503 gives. */
    public data class HealthMcpHub(public val on: Boolean, public val offReason: String? = null)

    /** What /health counts of the heads: how many are configured, how many are ready and how many failed to boot,
     *  each null when the daemon does not say. [failedReasons] is V4-394: each failed head's key and boot reason;
     *  empty on a healthy boot or an older daemon. */
    public data class HealthHeads(
        public val total: Int?,
        public val ready: Int?,
        public val failed: Int?,
        public val failedReasons: Map<String, String> = emptyMap(),
    )

    /** Which topology the daemon runs: its digest, and whether the config file has changed since. Each is null when
     *  the daemon does not say. */
    public data class HealthTopology(
        public val digest: String? = null,
        public val stale: Boolean? = null,
    )

    /** V4-452: what /health says about each head's quota, the two facts kept apart. [refusedUntil] is the reset of
     *  a refusal the head HOLDS, epoch seconds (`quotaResetAtEpochSeconds`); [full] is the provider's reading when
     *  it names a window fully used, beside a head that stays ready (`quotaFull`). Both empty for ready heads or an
     *  older daemon. */
    public data class HealthQuota(
        public val refusedUntil: Map<String, Long> = emptyMap(),
        public val full: Map<String, QuotaFull> = emptyMap(),
    )

    /** JW-05: the per-head runtime counters from /api/heads (bearer-guarded) — the
     *  local-origin vs provider-error split G20 built for exactly this diagnosis. */
    public data class HeadRuntime(
        public val key: String,
        public val localOriginErrors: Long,
        public val providerErrors: Long,
        public val rateLimit: splice.core.head.RateLimitHealth? = null,
    )

    /** The effective head-only trace switch this running head booted with. */
    public data class HeadTrace(public val enabled: Boolean)

    internal fun interface ResponseRead<T> {
        operator fun invoke(connection: HttpURLConnection): T
    }

    /** V4-230: what a /health probe found, each case its own. The probe read a refused connection, a
     *  timeout and a non-2xx alike as nothing answering, so a daemon too busy to answer inside
     *  [PROBE_TIMEOUT_MS] read "stopped" in doctor; in the console's own doctor, whose request that
     *  busy daemon was serving, 3 page loads in 6 said so (2026-09-25). */
    public sealed class HealthProbe {
        /** A splice-shaped listener answered, with [view]. */
        public data class Up(public val view: HealthView) : HealthProbe()

        /** Something accepted the connection and gave no answer within [waitedMs]. */
        public data class Slow(public val waitedMs: Int) : HealthProbe()

        /** Nothing listens on the port. */
        public data object Down : HealthProbe()

        /** A listener answered, but not with splice's /health; [detail] says how. */
        public data class Odd(public val detail: String) : HealthProbe()
    }

    /** What listens on [port], as its /health says. Unlike AdminSupport.daemonUp this accepts a STALE
     *  daemon: restart must be able to stop one. */
    public fun healthProbe(port: Int): HealthProbe =
        Cancellables.runCatchingCancellable {
            val connection = DaemonConnection.open("http://127.0.0.1:$port/health", PROBE_TIMEOUT_MS)
            try {
                val status = connection.responseCode
                val text = if (status in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
                    body(connection)
                } else {
                    ""
                }
                healthAnswer(status, text)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { failure ->
            when (failure) {
                is ConnectException -> HealthProbe.Down
                is SocketTimeoutException -> HealthProbe.Slow(PROBE_TIMEOUT_MS)
                else -> HealthProbe.Odd(SafeFailureText.render(failure))
            }
        }

    /** Classifies an answer already received; the HTTP path alone owns the probe's timing window. */
    internal fun healthAnswer(status: Int, body: String): HealthProbe =
        if (status !in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
            HealthProbe.Odd("it answered HTTP $status")
        } else {
            Cancellables.runCatchingCancellable { HealthProbe.Up(parseHealth(body)) }
                .getOrElse { HealthProbe.Odd("its answer is not splice's /health") }
        }

    /** The /health payload of any splice-shaped listener, or null when none answered: the probe's
     *  other cases, for a caller that only asks whether one did. */
    public fun healthView(port: Int): HealthView? = (healthProbe(port) as? HealthProbe.Up)?.view

    /** A /health body, read the one way the probe and the daemon's own doctor both read it. str()
     *  (JsonNull-filtering) keeps a foreign listener's {"version": null} from reading back as the
     *  literal string "null". */
    public fun parseHealth(body: String): HealthView = DaemonHealthReading.parse(body)

    /** One held turn off /health, or null where it names no login. */

    public fun healthVersion(port: Int): String? = healthView(port)?.version

    public fun headsRuntime(port: Int, bearer: String): Reading<List<HeadRuntime>> =
        Unanswered.readOrRefused {
            request("http://127.0.0.1:$port/api/heads", bearer = bearer) { parseHeadsRuntime(body(it)) }
        }

    /** An /api/heads body's per-head counters: a head without a key or a health object is skipped. */
    public fun parseHeadsRuntime(body: String): List<HeadRuntime> {
        val obj = json.parseToJsonElement(body).jsonObject
        return (obj["heads"] as? JsonArray).orEmpty().mapNotNull { el ->
            val head = el as? JsonObject ?: return@mapNotNull null
            val health = head["health"] as? JsonObject ?: return@mapNotNull null
            val providerTurns = JsonScalars.long(health, "provider_rate_limit_turns")?.takeIf { it >= 0 }
            val heldTurns = JsonScalars.long(health, "cooldown_held_turns")?.takeIf { it >= 0 }
            val rates = if (providerTurns == null || heldTurns == null) {
                null
            } else {
                splice.core.head.RateLimitHealth(providerTurns, heldTurns)
            }
            HeadRuntime(
                key = JsonScalars.str(head, "key") ?: return@mapNotNull null,
                localOriginErrors = JsonScalars.long(health, "localOriginErrors") ?: 0L,
                providerErrors = JsonScalars.long(health, "providerErrors") ?: 0L,
                rateLimit = rates,
            )
        }
    }

    /** The head ports the RUNNING daemon actually holds, or [Reading.Refused] when /api/heads is unreachable, [Reading.Malformed] when it answered nonsense.
     *
     *  The running daemon — not splice.toml — is authoritative for what is BOUND, and the two
     *  disagree in exactly the case `splice restart` exists to serve: an operator edits the file
     *  (including changing a head's port) and restarts to pick it up. Reading ports from the edited
     *  file would then check the NEW port while the old daemon still holds the OLD one, so the stop
     *  reports success against a port nothing ever bound. It also survives a malformed TOML, which
     *  no file-sourced list can. */
    public fun headPorts(port: Int, bearer: String): Reading<List<Int>> =
        Unanswered.readOrRefused {
            request("http://127.0.0.1:$port/api/heads", bearer = bearer) { connection ->
                val obj = json.parseToJsonElement(body(connection)).jsonObject
                (obj["heads"] as? JsonArray).orEmpty().mapNotNull { JsonScalars.int(it as? JsonObject, "port") }
            }
        }

    /** One head's credential as the DAEMON sees it. [verdict] is what upstream last said about a
     *  forwarded credential (V4-220 item 6b); [CredentialVerdict.Held] from a daemon that predates it
     *  (sent none), and [CredentialVerdict.Unverified] for one this build cannot read (V4-293). */
    public data class HeadAuthSeen(public val present: Boolean, public val verdict: CredentialVerdict)

    /** Per-head credential state as the DAEMON sees it (`/api/auth`): [Reading.Refused] when unreachable, [Reading.Malformed] when the body is unreadable.
     *  Doctor compares presence against the shell's to catch the exported-after-boot trap, and reads
     *  a client head's verdict because only the daemon sees upstream's answers. */
    public fun authSeen(port: Int, key: String): Reading<Map<String, HeadAuthSeen>> =
        Unanswered.readOrRefused {
            request("http://127.0.0.1:$port/api/auth", bearer = key) { parseAuthSeen(body(it)) }
        }

    /** An /api/auth body's per-head credential state. */
    public fun parseAuthSeen(body: String): Map<String, HeadAuthSeen> =
        json.parseToJsonElement(body).jsonObject.mapValues { (_, v) -> DaemonAuthReading.read(v.jsonObject) }

    /** A verdict that is sent but unreadable (a newer word, a dated state with no date) is not "nothing to
     *  verify": only a daemon that sent none predates verdicts (V4-293). */

    internal fun <T> request(
        url: String,
        method: String = "GET",
        bearer: String? = null,
        read: ResponseRead<T>,
    ): T? {
        val connection = DaemonConnection.open(url, PROBE_TIMEOUT_MS, method, bearer)
        return try {
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

/** One head's effective trace switch from the daemon's guarded config route. This is the same
 *  request shape doctor uses for a stopped-versus-booted comparison, not a fresh topology read. */
public class TraceConfigProbe {
    /** Null means no trustworthy answer, never trace off. */
    public fun read(port: Int, bearer: String, head: String): DaemonProbe.HeadTrace? =
        Cancellables.runCatchingCancellable {
            val key = FormEncoding.percentEncode(head)
            DaemonProbe.request("http://127.0.0.1:$port/api/config?head=$key", bearer = bearer) {
                parse(DaemonProbe.body(it))
            }
        }.fold(onSuccess = { it }, onFailure = { null })

    /** Parse only the typed effective switch; a missing or non-Boolean trace is not "off". */
    public fun parse(body: String): DaemonProbe.HeadTrace? {
        val effective = DaemonProbe.json.parseToJsonElement(body).jsonObject["effective"] as? JsonObject ?: return null
        val enabled = (effective["trace"] as? JsonPrimitive)?.takeUnless { it.isString }
            ?.booleanOrNull ?: return null
        return DaemonProbe.HeadTrace(enabled)
    }
}

private const val PROBE_TIMEOUT_MS = 400
