// NEW: V4-344 enumerates Claude Code history and primary transcripts by session id.
package splice.client.transcript

import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.sessions.transcript.SessionFiles
import splice.sessions.transcript.SessionHistoryEntry
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import splice.sessions.transcript.SessionHistorySource
import java.io.IOException
import java.nio.file.NoSuchFileException

// why: searches typed into one visit reuse the source scan, while new sessions appear soon after.
private const val CACHE_MS = 10_000L

/** Claude Code's history.jsonl and primary transcript files, joined by session id. A short cached
 *  snapshot means typing a search does not redo the join on every keystroke, and what each refresh learned from a
 *  file is kept while the file is unchanged: a refresh lists and stats the transcripts and reads only a new or
 *  changed one, and reads only what was appended to history.jsonl. */
public class TranscriptHistoryIndex(
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : SessionHistorySource {
    private data class Cached(val roots: List<SessionHistoryRoot>, val at: Long, val scan: SessionHistoryScan)
    private val monitor = Any()
    private var cached: Cached? = null
    private val transcripts = TranscriptFactsCache()
    private val history = HistoryRowsCache()

    override fun scan(roots: List<SessionHistoryRoot>): SessionHistoryScan = synchronized(monitor) {
        val now = clock()
        cached?.takeIf { it.roots == roots && now - it.at in 0 until CACHE_MS }?.let { return@synchronized it.scan }
        val transcriptPass = transcripts.pass()
        val historyPass = history.pass()
        HistoryScanBuilder(roots, transcriptPass, historyPass).read().also {
            transcriptPass.commit()
            historyPass.commit()
            cached = Cached(roots.toList(), now, it)
        }
    }
}

private class HistoryScanBuilder(
    private val roots: List<SessionHistoryRoot>,
    private val transcriptFacts: TranscriptFactsCache.Pass,
    private val historyRows: HistoryRowsCache.Pass,
) {
    private val state = HistoryScanState()
    private val sessions = state.sessions
    private val tree = TranscriptTreeReader(state, transcriptFacts)

    fun read(): SessionHistoryScan {
        roots.forEach(::history)
        roots.forEach(tree::read)
        val entries = sessions.values.map(HistoryFacts::entry)
        return SessionHistoryScan(entries, state.skipped.toMap(), state.errors.toList())
    }

    private fun history(root: SessionHistoryRoot) {
        val file = root.dir.resolve("history.jsonl")
        try {
            historyRows.rows(file).forEach { historyRow(it, root.head) }
        } catch (_: NoSuchFileException) {
            // This config tree has no history file; its primary transcripts still count.
        } catch (error: IOException) {
            state.errors += "$file: ${SafeFailureText.render(error)}"
        }
    }

    private fun historyRow(row: HistoryRow, head: String?) {
        when (row) {
            HistoryRow.Malformed -> state.skip("malformed history row")
            HistoryRow.WithoutId -> state.skip("history row without session id")
            is HistoryRow.Prompt -> prompt(row, head)
        }
    }

    private fun prompt(row: HistoryRow.Prompt, head: String?) {
        val facts = sessions.getOrPut(row.id) { HistoryFacts(row.id) }
        if (facts.hasHistory) state.skip("coalesced history row")
        facts.hasHistory = true
        if (!facts.accepts(row.timestamp)) return
        facts.historyAt = row.timestamp
        facts.historyName = row.title ?: facts.historyName
        facts.project = row.project ?: facts.project
        if (head != null) facts.head = head
    }
}

internal class HistoryFacts(private val id: String) {
    var hasHistory: Boolean = false
    var hasTranscript: Boolean = false
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
        ),
    )
}
