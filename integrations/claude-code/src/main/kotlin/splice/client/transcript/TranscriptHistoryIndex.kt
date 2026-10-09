// NEW: V4-344 enumerates Claude Code history and primary transcripts by session id.
package splice.client.transcript

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.client.resume.ResumableTranscript
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.sessions.transcript.SessionFiles
import splice.sessions.transcript.SessionHistoryEntry
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import splice.sessions.transcript.SessionHistorySource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path

// why: searches typed into one visit reuse the source scan, while new sessions appear soon after.
private const val CACHE_MS = 10_000L

private val SESSION_FILE = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jsonl",
)
private val HISTORY_ID = Regex("[A-Za-z0-9_-]{1,128}")

/** Claude Code's history.jsonl and primary transcript files, joined by session id. A short cached
 *  snapshot means typing a search does not reread every transcript on every keystroke. */
public class TranscriptHistoryIndex(
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : SessionHistorySource {
    private data class Cached(val roots: List<SessionHistoryRoot>, val at: Long, val scan: SessionHistoryScan)
    private val monitor = Any()
    private var cached: Cached? = null

    override fun scan(roots: List<SessionHistoryRoot>): SessionHistoryScan = synchronized(monitor) {
        val now = clock()
        cached?.takeIf { it.roots == roots && now - it.at in 0 until CACHE_MS }?.let { return@synchronized it.scan }
        HistoryScanBuilder(roots).read().also { cached = Cached(roots.toList(), now, it) }
    }
}

private class HistoryScanBuilder(private val roots: List<SessionHistoryRoot>) {
    private val json = Json
    private val sessions = linkedMapOf<String, HistoryFacts>()
    private val skipped = mutableMapOf<String, Int>()
    private val errors = mutableListOf<String>()
    private val seenProjects = mutableSetOf<Path>()

    fun read(): SessionHistoryScan {
        roots.forEach(::history)
        roots.forEach(::transcripts)
        return SessionHistoryScan(sessions.values.map(HistoryFacts::entry), skipped.toMap(), errors.toList())
    }

    private fun skip(reason: String) {
        skipped[reason] = (skipped[reason] ?: 0) + 1
    }

    private fun history(root: SessionHistoryRoot) {
        val file = root.dir.resolve("history.jsonl")
        try {
            Files.newBufferedReader(file).use { reader ->
                reader.forEachLine { historyLine(it, root.head) }
            }
        } catch (_: NoSuchFileException) {
            // This config tree has no history file; its primary transcripts still count.
        } catch (error: IOException) {
            errors += "$file: ${SafeFailureText.render(error)}"
        }
    }

    private fun historyLine(line: String, head: String?) {
        val record = parse(line)
        val id = JsonScalars.str(record, "sessionId")
        if (id == null || !HISTORY_ID.matches(id)) {
            skip(if (record == null) "malformed history row" else "history row without session id")
            return
        }
        val facts = sessions.getOrPut(id) { HistoryFacts(id) }
        if (facts.hasHistory) skip("coalesced history row")
        facts.hasHistory = true
        val timestamp = JsonScalars.long(record, "timestamp")
        if (!facts.accepts(timestamp)) return
        facts.historyAt = timestamp
        facts.historyName = TranscriptMetadata.title(JsonScalars.str(record, "display")) ?: facts.historyName
        facts.project = JsonScalars.str(record, "project")?.takeIf(String::isNotBlank) ?: facts.project
        if (head != null) facts.head = head
    }

    private fun transcripts(root: SessionHistoryRoot) {
        val projects = root.dir.resolve("projects")
        try {
            val canonical = projects.toRealPath()
            if (!seenProjects.add(canonical)) return
            Files.newDirectoryStream(projects).use { folders ->
                folders.forEach { inspectFolder(it, canonical, root.head) }
            }
        } catch (_: NoSuchFileException) {
            // An uncreated projects tree is no evidence of a lost history file.
        } catch (error: IOException) {
            errors += "$projects: ${SafeFailureText.render(error)}"
        }
    }

    private fun inspectFolder(folder: Path, projects: Path, head: String?) {
        try {
            if (!folder.toRealPath().startsWith(projects)) {
                skip("project path outside tree")
                return
            }
            Files.newDirectoryStream(folder).use { entries ->
                entries.forEach { inspectFile(it, projects, head) }
            }
        } catch (_: NotDirectoryException) {
            skip("non-project artifact")
        } catch (error: IOException) {
            errors += "$folder: ${SafeFailureText.render(error)}"
        }
    }

    private fun inspectFile(file: Path, projects: Path, head: String?) {
        when {
            Files.isDirectory(file) -> skip("nested artifacts")
            !file.fileName.toString().endsWith(".jsonl") -> skip("non-jsonl artifact")
            !SESSION_FILE.matches(file.fileName.toString()) -> skip("non-session jsonl")
            else -> readTranscript(file, projects, head)
        }
    }

    private fun trustedPrimary(file: Path, projects: Path): Boolean =
        Files.isRegularFile(file) && file.toRealPath().startsWith(projects)

    private fun readTranscript(file: Path, projects: Path, head: String?) {
        try {
            if (!trustedPrimary(file, projects)) {
                skip("untrusted transcript path")
                return
            }
            val id = file.fileName.toString().removeSuffix(".jsonl")
            val facts = sessions.getOrPut(id) { HistoryFacts(id) }
            val modified = Files.getLastModifiedTime(file).toMillis()
            val eligible = ResumableTranscript.accepts(file)
            facts.transcriptContent = facts.transcriptContent || eligible
            if (!facts.hasTranscript || modified >= (facts.transcriptAt ?: 0L)) {
                val metadata = TranscriptMetadata.read(file)
                facts.transcriptAt = modified
                facts.transcriptName = metadata?.first
                facts.project = facts.project ?: metadata?.second
                if (head != null) facts.head = head
            }
            facts.hasTranscript = true
        } catch (error: IOException) {
            errors += "$file: ${SafeFailureText.render(error)}"
        }
    }

    private fun parse(line: String): JsonObject? = try {
        json.parseToJsonElement(line) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}

private class HistoryFacts(private val id: String) {
    var hasHistory: Boolean = false
    var hasTranscript: Boolean = false
    var transcriptContent: Boolean = false
    var historyName: String? = null
    var transcriptName: String? = null
    var project: String? = null
    var head: String? = null
    var historyAt: Long? = null
    var transcriptAt: Long? = null

    fun accepts(timestamp: Long?): Boolean {
        val previous = historyAt ?: return true
        return timestamp != null && timestamp >= previous
    }

    fun entry(): SessionHistoryEntry = SessionHistoryEntry(
        sessionId = id,
        name = transcriptName ?: historyName,
        project = project,
        head = head,
        updatedAt = historyAt ?: transcriptAt,
        files = SessionFiles(
            hasHistory = hasHistory,
            hasTranscript = hasTranscript,
            resumable = hasTranscript && transcriptContent,
        ),
    )
}
