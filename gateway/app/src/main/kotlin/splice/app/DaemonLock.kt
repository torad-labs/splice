// PORT-OF: the launcher's EADDRINUSE quiet-exit intent @ pre-public-port-baseline, adapted for the single
// daemon (P4-SUP slot): one process binds control_port AND every head port, so the per-port
// trick doesn't compose. A flock on ~/.claude-codex/state/daemon.lock is the single-flight
// startup gate — the loser waits briefly, health-checks the winner, exits 0 LOUD (never a loop).
package splice.app

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
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** Try to acquire the exclusive daemon lock. Returns true if this process is the winner.
     *  Note: FileLock is JVM-wide — a second lock attempt within the SAME process throws
     *  OverlappingFileLockException (separate processes get null); both mean "held by another". */
    public fun tryAcquire(): Boolean {
        Files.createDirectories(lockFile.parent)
        val ch = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val fl = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        return if (fl == null) {
            ch.close()
            false
        } else {
            channel = ch
            lock = fl
            true
        }
    }

    override fun close() {
        Cancellables.discard(
            runCatching { lock?.release() },
            "process-exit cleanup; the OS reclaims the lock regardless",
        )
        Cancellables.discard(
            runCatching { channel?.close() },
            "process-exit cleanup; the OS reclaims the fd regardless",
        )
    }
}

/**
 * HTTP probes of a running daemon — the lock loser health-checks the winner, and doctor/restart
 * read the same /health and /api surfaces. File-scope object (not a companion, not a new file):
 * [DaemonLock] has a required ctor so no-arg helpers cannot hang on the instance, and a nested
 * companion is banned. Nested payload types so they are not a column-0 type bill
 * (concentration, 2026-08-19). CLI keeps `HealthView` via a typealias in DoctorCheckTypes.
 */
internal object DaemonProbe {

    private val json = Json { ignoreUnknownKeys = true }

    /** JW-02: what /health actually says — the version AND the head counters the launch shim
     *  already waits on. */
    internal data class HealthView(
        val version: String?,
        val heads: Int?,
        val readyHeads: Int?,
        val failedHeads: Int?,
        val topologyDigest: String? = null,
        val configPath: String? = null,
        val topologyStale: Boolean? = null,
        val ok: Boolean? = null,
        val turnPathStalled: List<String> = emptyList(),
    )

    /** JW-05: the per-head runtime counters from /api/heads (bearer-guarded) — the
     *  local-origin vs provider-error split G20 built for exactly this diagnosis. */
    internal data class HeadRuntime(val key: String, val localOriginErrors: Long, val providerErrors: Long)

    internal fun interface ResponseRead<T> {
        operator fun invoke(connection: HttpURLConnection): T
    }

    /** The /health payload of any splice-shaped listener, or null when nothing answers.
     *  Unlike AdminSupport.daemonUp this accepts a STALE daemon — restart must be able to stop one.
     *  str() (JsonNull-filtering) keeps a foreign listener's {"version": null} from reading back as
     *  the literal string "null". */
    internal fun healthView(port: Int): HealthView? = Cancellables.runCatchingCancellable {
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

    internal fun healthVersion(port: Int): String? = healthView(port)?.version

    internal fun headsRuntime(port: Int, bearer: String): List<HeadRuntime>? = Cancellables.runCatchingCancellable {
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
    internal fun headPorts(port: Int, bearer: String): List<Int>? = Cancellables.runCatchingCancellable {
        request("http://127.0.0.1:$port/api/heads", bearer = bearer) { connection ->
            val obj = json.parseToJsonElement(body(connection)).jsonObject
            (obj["heads"] as? JsonArray).orEmpty().mapNotNull { JsonScalars.int(it as? JsonObject, "port") }
        }
    }.getOrNull()

    /** Per-head credential presence as the DAEMON sees it (`/api/auth`), or null when unreachable.
     *  Doctor compares this against shell-side presence to catch the exported-after-boot trap. */
    internal fun authPresence(port: Int, key: String): Map<String, Boolean>? = Cancellables.runCatchingCancellable {
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
