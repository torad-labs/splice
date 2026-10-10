// NEW: Oct 10, 2026 — the record of every session splice has seen, kept past Claude Code's own registration.
//
// WHY A RECORD OF OUR OWN. Claude Code deletes a session's registration once its process is gone (a real machine
// held 33 registrations and not one dead pid, Oct 10), so a listing read only from those files has no ended
// sessions: Sessions' Ended fold stays empty and Requests cannot name the session a row came from. This keeps
// what a card needs to name one, and nothing else: its id, name, folder, head and last activity. Never a word of
// its transcript (splice-lead, Oct 10).
//
// IT IS HISTORY. One file per session, written with its last activity as the file's own time, so the one history
// window ages it like every other store (HistoryWiring through KeptHistoryStores): a read leaves out what the
// window no longer keeps, and the save that shortens the window deletes it in the same cut.
package splice.sessions.registry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.KeptHistory
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** The folder under the state directory the record lives in, which Your data counts as [SEEN_SESSIONS_KEY]. */
public const val SEEN_SESSIONS_DIR: String = "sessions-seen"

/** The key Your data names this store by, in a history read's held stores. */
public const val SEEN_SESSIONS_KEY: String = "session_record"

// why: a session id is a file name here, so only the shape Claude Code writes is ever one; anything else could
// name a path outside the record.
private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,128}")

// why: a record is five short fields; anything larger is not one of ours and is not read.
private const val MAX_SEEN_BYTES = 8L shl 10

/** What the record keeps of one session. */
internal data class SeenSession(
    val sessionId: String,
    val name: String?,
    val cwd: String?,
    val head: String?,
    val lastActivityMs: Long,
)

/** Every session splice has listed, kept by its last activity under the history window [kept] reads now. */
public class SeenSessions(
    private val dir: Path,
    private val kept: KeptHistory,
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    // What was last written per session, so a listing read every few seconds writes only what changed.
    private val written = HashMap<String, SeenSession>()

    /** Records [records] that name a session, writing a file only when what it keeps changed. A record that cannot
     *  be written is skipped: the listing it came from is still served whole. */
    @Synchronized
    internal fun note(records: List<SessionRecord>) {
        records.mapNotNull(::seen).forEach { seen ->
            if (written[seen.sessionId] == seen) return@forEach
            Cancellables.runCatchingCancellable { write(seen) }.onSuccess { written[seen.sessionId] = seen }
        }
    }

    /** The sessions recorded that [present] does not hold and the window still keeps, newest first. */
    internal fun absent(present: Set<String>): List<SeenSession> {
        val cutoff = kept.now().cutoffMs(clock()) ?: Long.MIN_VALUE
        return files().asSequence()
            .filter { it.fileName.toString().removeSuffix(".json") !in present }
            .mapNotNull(::read)
            .filter { it.lastActivityMs >= cutoff }
            .sortedByDescending { it.lastActivityMs }
            .toList()
    }

    private fun seen(record: SessionRecord): SeenSession? {
        val id = record.sessionId?.takeIf(SESSION_ID::matches) ?: return null
        val at = listOfNotNull(record.process.updatedAt, record.status.updatedAt, record.process.startedAt).maxOrNull()
            ?: return null
        val earlier = written[id] ?: read(dir.resolve("$id.json"))
        return SeenSession(
            sessionId = id,
            name = record.name ?: earlier?.name,
            cwd = record.process.cwd ?: earlier?.cwd,
            head = record.head ?: earlier?.head,
            lastActivityMs = maxOf(at, earlier?.lastActivityMs ?: at),
        )
    }

    private fun write(seen: SeenSession) {
        val file = dir.resolve("${seen.sessionId}.json")
        val body = buildJsonObject {
            put("session_id", seen.sessionId)
            seen.name?.let { put("name", it) }
            seen.cwd?.let { put("cwd", it) }
            seen.head?.let { put("head", it) }
            put("last_activity_ms", seen.lastActivityMs)
        }
        SecureFile.writeAtomic0600(file, body.toString())
        // The file's own time is its last activity, which is what the history window ages it by.
        Files.setLastModifiedTime(file, FileTime.fromMillis(seen.lastActivityMs))
    }

    private fun read(file: Path): SeenSession? {
        val obj = text(file)?.let { JsonScalars.objectOrNull(json, it) } ?: return null
        val id = JsonScalars.str(obj, "session_id")?.takeIf(SESSION_ID::matches)
        val at = JsonScalars.long(obj, "last_activity_ms")
        return if (id == null || at == null) {
            null
        } else {
            SeenSession(id, JsonScalars.str(obj, "name"), JsonScalars.str(obj, "cwd"), JsonScalars.str(obj, "head"), at)
        }
    }

    /** A record file's text, or null when it is missing, unreadable or larger than any record. */
    private fun text(file: Path): String? = try {
        if (Files.size(file) > MAX_SEEN_BYTES) null else PathProbe.text(file)
    } catch (_: IOException) {
        null
    }

    private fun files(): List<Path> = try {
        Files.newDirectoryStream(dir, "*.json").use { stream ->
            stream.filterNot { it.fileName.toString().startsWith(".") }
        }
    } catch (_: NoSuchFileException) {
        emptyList()
    } catch (_: IOException) {
        emptyList()
    }
}
