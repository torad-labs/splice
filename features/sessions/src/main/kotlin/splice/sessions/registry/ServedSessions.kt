// A headless `claude -p` run never writes ~/.claude/sessions, so the registry cannot list it. A turn admitted
// through Splice is the evidence it ran. One row per session id, however many processes served it: the latest
// note wins. The listing reads the map once. It does not walk processes or files per session.
package splice.sessions.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.AsyncFileIo
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A turn named a session. [served] is true only once a turn of it was admitted. [owner] is the launch that sent
 *  the request, when the request carried one. */
public fun interface ServedTurn {
    public fun note(sessionId: String?, head: String?, owner: String?, served: Boolean)
}

/** A head built without a console notes nothing. */
public object NoServedTurn : ServedTurn {
    override fun note(sessionId: String?, head: String?, owner: String?, served: Boolean): Unit = Unit
}

/** What a launch recorded for the process that sent a turn: its labels, folder and pid. */
public data class ServedLaunch(
    val labels: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val pid: Long? = null,
)

/** Looks up the launch a request's owner header names. One file on a miss, never a directory walk on the listing. */
public fun interface ServedLaunches {
    public fun of(owner: String): ServedLaunch?
}

// why: the listing already sends only the newest hundred gone rows; keeping more here would never be shown.
private const val GONE_KEPT = 100

// why: a session is served by a handful of processes, not a history of every pid it ever had.
private const val PIDS_KEPT = 8

// why: pruning walks process births, so it waits until the index is past the gone rows it keeps.
private const val PRUNE_AFTER = GONE_KEPT + 32

private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,128}")

@Serializable
private data class StoredNote(
    val sessionId: String,
    val head: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val at: Long = 0L,
    val pid: Long? = null,
    val pids: List<Long> = emptyList(),
    val served: Boolean = false,
)

private data class Note(
    val sessionId: String,
    val head: String?,
    val labels: Map<String, String>,
    val cwd: String?,
    val at: Long,
    val pids: List<Long>,
    val served: Boolean,
)

/** Sessions that have sent a turn through Splice, keyed by session id. */
public class ServedSessions(
    private val dir: Path? = null,
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
    private val alive: PidAlive = PidAlive { pid ->
        pid > 0 && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    },
    private val launches: ServedLaunches = ServedLaunches { null },
) : ServedTurn {
    private val json = Json { ignoreUnknownKeys = true }
    private val notes = ConcurrentHashMap<String, Note>()
    private val loaded = AtomicBoolean(false)

    override fun note(sessionId: String?, head: String?, owner: String?, served: Boolean) {
        if (sessionId == null || !SESSION_ID.matches(sessionId)) return
        ensureLoaded()
        val launch = owner?.let(launches::of)
        val incoming = Note(
            sessionId,
            head,
            launch?.labels.orEmpty(),
            launch?.cwd,
            clock(),
            listOfNotNull(launch?.pid),
            served,
        )
        notes.merge(sessionId, incoming, ::newer)?.let(::write)
        if (notes.size > PRUNE_AFTER) prune()
    }

    /** The labels recorded for [sessionId], or none. */
    public fun labelsOf(sessionId: String?): Map<String, String> {
        if (sessionId == null) return emptyMap()
        ensureLoaded()
        return notes[sessionId]?.labels.orEmpty()
    }

    /** Served sessions [present] does not already list, newest activity first. */
    public fun absent(present: Set<String>): List<SessionRecord> {
        ensureLoaded()
        val now = clock()
        return notes.values.asSequence()
            .filter { it.served && it.sessionId !in present }
            .sortedByDescending { it.at }
            .map { record(it, now) }
            .toList()
    }

    private fun newer(old: Note, new: Note): Note {
        val later = new.at >= old.at
        return Note(
            sessionId = old.sessionId,
            head = new.head ?: old.head,
            labels = labelsOf(old, new, later),
            cwd = kept(later, new.cwd, old.cwd),
            at = if (new.served) maxOf(old.at, new.at) else old.at,
            pids = (new.pids + old.pids).distinct().take(PIDS_KEPT),
            served = old.served || new.served,
        )
    }

    private fun labelsOf(old: Note, new: Note, later: Boolean): Map<String, String> = when {
        new.labels.isEmpty() -> old.labels
        later -> new.labels
        else -> old.labels.ifEmpty { new.labels }
    }

    private fun kept(later: Boolean, incoming: String?, held: String?): String? =
        if (later) incoming ?: held else held ?: incoming

    private fun record(note: Note, now: Long): SessionRecord {
        val availability = availability(note, now)
        return SessionRecord(
            sessionId = note.sessionId,
            name = null,
            status = SessionStatus(),
            route = note.head?.let { SessionRoute.Head(it) } ?: SessionRoute.Unknown,
            availability = availability,
            process = SessionProcess(
                pid = note.pids.firstOrNull(alive::invoke) ?: note.pids.firstOrNull(),
                cwd = note.cwd,
                startedAt = null,
                updatedAt = note.at,
                messagingSocketPath = null,
            ),
            client = SessionClient(kind = "headless", version = null),
        )
    }

    private fun availability(note: Note, now: Long): SessionAvailability {
        val heard = note.at >= now - STALE_AFTER_MS
        val living = note.pids.any(alive::invoke)
        return when {
            living && heard -> SessionAvailability.LIVE
            living -> SessionAvailability.STALE
            heard && note.pids.isEmpty() -> SessionAvailability.LIVE
            else -> SessionAvailability.GONE
        }
    }

    private fun prune() {
        val gone = notes.values.filter { it.served && it.pids.none(alive::invoke) }.sortedBy { it.at }
        val extra = gone.size - GONE_KEPT
        if (extra <= 0) return
        gone.take(extra).forEach { drop(it.sessionId) }
    }

    private fun drop(sessionId: String) {
        notes.remove(sessionId)
        dir?.let { folder ->
            AsyncFileIo.submitFor(folder.resolve("$sessionId.json")) {
                Files.deleteIfExists(folder.resolve("$sessionId.json"))
            }
        }
    }

    private fun write(note: Note) {
        val folder = dir ?: return
        if (!note.served && note.labels.isEmpty()) return
        val file = folder.resolve("${note.sessionId}.json")
        val body = json.encodeToString(
            StoredNote(
                note.sessionId,
                note.head,
                note.labels,
                note.cwd,
                note.at,
                note.pids.firstOrNull(),
                note.pids,
                note.served,
            ),
        )
        AsyncFileIo.submitFor(file) {
            if (SecureFile.ownerOnlyDirectory(folder) == null) SecureFile.writeAtomic0600(file, body)
        }
    }

    /** A file that cannot be read is not a session. The listing goes on with the ones that can. */
    private fun readNote(file: Path): StoredNote? = try {
        json.decodeFromString(Files.readString(file))
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun ensureLoaded() {
        val folder = dir ?: return
        if (!loaded.compareAndSet(false, true)) return
        val files = try {
            Files.newDirectoryStream(folder, "*.json").use { it.toList() }
        } catch (_: IOException) {
            return
        }
        files.forEach { file ->
            val stored = readNote(file) ?: return@forEach
            if (!SESSION_ID.matches(stored.sessionId)) return@forEach
            notes.putIfAbsent(
                stored.sessionId,
                Note(
                    stored.sessionId,
                    stored.head,
                    stored.labels,
                    stored.cwd,
                    stored.at,
                    stored.pids.ifEmpty { listOfNotNull(stored.pid) },
                    stored.served,
                ),
            )
        }
    }
}

/** The served-session index, read when a listing is asked for, not when the route is built. */
public fun interface ServedSessionIndex {
    public operator fun invoke(): ServedSessions?
}

/** [inner]'s listing plus one row per served session it does not already name. */
public class ServedSessionSource(
    private val inner: SessionSource,
    private val served: ServedSessionIndex,
) : SessionSource {
    override fun read(): List<SessionRecord> = list().sessions

    override fun list(): SessionListing {
        val listing = inner.list()
        val extra = served()?.absent(listing.sessions.mapNotNullTo(HashSet()) { it.sessionId }).orEmpty()
        return if (extra.isEmpty()) listing else listing.copy(sessions = listing.sessions + extra)
    }
}
