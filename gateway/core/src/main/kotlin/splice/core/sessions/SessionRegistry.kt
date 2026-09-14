// NEW: v0.4.0 FEATURES.md §4 — a read-only view of Claude Code's own session registry,
// ~/.claude/sessions/<pid>.json (one file per interactive session; headless `claude -p` runs
// never register). Every field is optional because Claude Code owns the schema and may add,
// rename or omit keys; a malformed file is skipped, never fatal. Availability is derived, never
// trusted from the file: a registration whose pid is gone is GONE whatever its status says, and
// one whose updatedAt is older than the stale window is STALE (alive, but not heard from).
package splice.core.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

private const val DEFAULT_STALE_MS = 30L * 60L * 1000L

/** A registration is a few hundred bytes; the directory is shared by every head, so a runaway or
 *  foreign file there is skipped rather than read whole on every poll. */
private const val MAX_RECORD_BYTES = 64L shl 10

public enum class SessionAvailability { LIVE, STALE, GONE }

public data class SessionRecord(
    val pid: Long?,
    val sessionId: String?,
    val cwd: String?,
    val name: String?,
    val kind: String?,
    val version: String?,
    val status: String?,
    val statusUpdatedAt: Long?,
    val startedAt: Long?,
    val updatedAt: Long?,
    val messagingSocketPath: String?,
    /** The splice head this session talks to, or null when splice did not launch it. */
    val head: String?,
    val availability: SessionAvailability,
) {
    /** The cross-session address a SendMessage can use when the session carries no name. */
    public val address: String? get() = messagingSocketPath?.let { "uds:$it" }
}

/** Is the process alive? Seam so tests can decide without spawning. */
public fun interface PidAlive {
    public operator fun invoke(pid: Long): Boolean
}

/** When the process started (epoch ms), or null when unknown. Seam so tests decide without spawning. */
public fun interface PidStartedAt {
    public operator fun invoke(pid: Long): Long?
}

/** A process that started this long after its registration's startedAt is a reused pid, not the session. */
private const val PID_REUSE_TOLERANCE_MS = 300_000L

/** Which splice head launched this pid, if any. */
public fun interface HeadOfPid {
    public operator fun invoke(pid: Long): String?
}

public class SessionRegistry(
    private val sessionsDir: Path,
    private val headOf: HeadOfPid,
    private val pidAlive: PidAlive = PidAlive { pid ->
        pid > 0 && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    },
    private val pidStartedAt: PidStartedAt = PidStartedAt { pid ->
        ProcessHandle.of(pid).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(null)
    },
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
    private val staleAfterMs: Long = DEFAULT_STALE_MS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every readable registration, newest activity first. */
    public fun read(): List<SessionRecord> = entries().mapNotNull(::record).sortedByDescending { it.updatedAt ?: 0L }

    private fun entries(): List<Path> = Cancellables
        .runCatchingCancellable { Files.newDirectoryStream(sessionsDir, "*.json").use { it.toList() } }
        .getOrDefault(emptyList())

    private fun record(file: Path): SessionRecord? {
        val obj = Cancellables
            .runCatchingCancellable {
                if (Files.size(file) > MAX_RECORD_BYTES) null else json.parseToJsonElement(Files.readString(file))
            }
            .getOrNull() as? JsonObject ?: return null
        val pid = JsonScalars.long(obj, "pid")
        val updatedAt = JsonScalars.long(obj, "updatedAt")
        val availability = availability(pid, updatedAt, JsonScalars.long(obj, "startedAt"))
        return SessionRecord(
            pid = pid,
            sessionId = JsonScalars.str(obj, "sessionId"),
            cwd = JsonScalars.str(obj, "cwd"),
            name = JsonScalars.str(obj, "name"),
            kind = JsonScalars.str(obj, "kind"),
            version = JsonScalars.str(obj, "version"),
            status = JsonScalars.str(obj, "status"),
            statusUpdatedAt = JsonScalars.long(obj, "statusUpdatedAt"),
            startedAt = JsonScalars.long(obj, "startedAt"),
            updatedAt = updatedAt,
            messagingSocketPath = JsonScalars.str(obj, "messagingSocketPath"),
            head = pid?.takeIf { availability != SessionAvailability.GONE }?.let(headOf::invoke),
            availability = availability,
        )
    }

    private fun reusedPid(pid: Long, startedAt: Long?): Boolean =
        startedAt != null && (pidStartedAt(pid) ?: 0L) > startedAt + PID_REUSE_TOLERANCE_MS

    /** A pid that is absent or not a real process id (0, negative) is GONE for this one row only; so
     *  is a live pid whose process started long after the registration (the pid was reused). */
    private fun availability(pid: Long?, updatedAt: Long?, startedAt: Long?): SessionAvailability = when {
        pid == null || pid <= 0 || !pidAlive(pid) || reusedPid(pid, startedAt) -> SessionAvailability.GONE
        updatedAt == null || clock() - updatedAt > staleAfterMs -> SessionAvailability.STALE
        else -> SessionAvailability.LIVE
    }
}
