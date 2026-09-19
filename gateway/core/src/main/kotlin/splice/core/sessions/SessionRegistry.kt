// NEW: v0.4.0 FEATURES.md §4 — a read-only view of Claude Code's own session registry,
// ~/.claude/sessions/<pid>.json (one file per interactive session; headless `claude -p` runs
// never register). Every field is optional because Claude Code owns the schema and may add,
// rename or omit keys; a malformed file is skipped, never fatal. Availability is derived, never
// trusted from the file: a registration whose pid is gone is GONE whatever its status says, and
// one whose updatedAt is older than the stale window is STALE (alive, but not heard from). The
// pid is read in the DOMAIN the file names (PidIdentity): another namespace's pid, or a pid whose
// start time moved since the registration, is GONE.
package splice.core.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private const val DEFAULT_STALE_MS = 30L * 60L * 1000L

/** A registration is a few hundred bytes; the directory is shared by every head, so a runaway or
 *  foreign file there is skipped rather than read whole on every poll. */
private const val MAX_RECORD_BYTES = 64L shl 10

public enum class SessionAvailability { LIVE, STALE, GONE }

/** Every readable registration, and why the directory could not be enumerated when it could not:
 *  a missing directory is genuinely no sessions, a permission failure or a file in its place is
 *  not, and both callers say which (review 2026-09-14). */
public data class SessionListing(val sessions: List<SessionRecord>, val error: String? = null)

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
    private val identity: PidIdentity = ProcPidIdentity(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every readable registration, newest activity first. */
    public fun read(): List<SessionRecord> = list().sessions

    /** [read] plus the enumeration failure, when the directory exists but could not be listed. */
    public fun list(): SessionListing {
        val entries = Cancellables
            .runCatchingCancellable { Files.newDirectoryStream(sessionsDir, "*.json").use { it.toList() } }
        val error = entries.exceptionOrNull()?.takeUnless { it is NoSuchFileException }
        val records = entries.getOrDefault(emptyList()).mapNotNull(::record).sortedByDescending { it.updatedAt ?: 0L }
        return SessionListing(records, error?.let { "$sessionsDir: ${SafeFailureText.render(it)}" })
    }

    private fun record(file: Path): SessionRecord? {
        val obj = Cancellables
            .runCatchingCancellable {
                if (Files.size(file) > MAX_RECORD_BYTES) null else json.parseToJsonElement(Files.readString(file))
            }
            .getOrNull() as? JsonObject ?: return null
        val pid = JsonScalars.long(obj, "pid")
        val updatedAt = JsonScalars.long(obj, "updatedAt")
        val availability = availability(
            pid,
            updatedAt,
            JsonScalars.long(obj, "startedAt"),
            JsonScalars.str(obj, "pidDomain"),
            JsonScalars.str(obj, "procStart"),
        )
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

    /** Claude Code's own identity facts decide first: a domain that is not this host's (the pid is
     *  another namespace's), or a start time that is not the running process's (the pid was reused).
     *  Only a registration without them falls back to the start-time tolerance. */
    private fun foreignPid(pid: Long, domain: String?, procStart: String?, startedAt: Long?): Boolean {
        val hostDomain = identity.hostDomain()
        val judged = domain != null && hostDomain != null
        if (judged && domain != hostDomain) return true
        val start = procStart?.let { identity.procStart(pid) }
        if (procStart != null && start != null) return procStart != start
        return startedAt != null && (pidStartedAt(pid) ?: 0L) > startedAt + PID_REUSE_TOLERANCE_MS
    }

    private fun gone(pid: Long, domain: String?, procStart: String?, startedAt: Long?): Boolean =
        !pidAlive(pid) || foreignPid(pid, domain, procStart, startedAt)

    /** A pid that is absent or not a real process id (0, negative) is GONE for this one row only; so
     *  is a live pid that is not the registered process (foreignPid). */
    private fun availability(
        pid: Long?,
        updatedAt: Long?,
        startedAt: Long?,
        domain: String?,
        procStart: String?,
    ): SessionAvailability = when {
        pid == null || pid <= 0 || gone(pid, domain, procStart, startedAt) -> SessionAvailability.GONE
        updatedAt == null || clock() - updatedAt > staleAfterMs -> SessionAvailability.STALE
        else -> SessionAvailability.LIVE
    }
}
