// NEW: V4-344 enumerates Claude Code history and primary transcripts by session id.
package splice.client.transcript

import kotlinx.coroutines.CancellationException
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.sessions.transcript.SessionFiles
import splice.sessions.transcript.SessionHistoryEntry
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import splice.sessions.transcript.SessionHistorySource
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

// why: searches typed into one visit reuse the source scan, while new sessions appear soon after.
private const val CACHE_MS = 10_000L
private const val MAX_SCAN_ENTRIES = 4_096
private const val MAX_SCAN_BYTES = 4L shl 20
private const val SCAN_ENTRY_BYTES = 160L
private const val SCAN_ERROR_BYTES = 48L

/** Claude Code's history.jsonl and primary transcript files, joined by session id. A short cached
 *  snapshot means typing a search does not redo the join on every keystroke, and what each refresh learned from a
 *  file is kept while the file is unchanged: a refresh lists and stats the transcripts and reads only a new or
 *  changed one, and reads only what was appended to retained history files. Caches have entry and byte bounds;
 *  larger scans still return every session and share their result with overlapping readers. */
public class TranscriptHistoryIndex(
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : SessionHistorySource {
    private data class Cached(val roots: List<SessionHistoryRoot>, val at: Long, val scan: SessionHistoryScan)
    private data class Refresh(val roots: List<SessionHistoryRoot>, val result: CompletableFuture<SessionHistoryScan>)
    private val monitor = Any()
    private var cached: Cached? = null
    private var refreshing: Refresh? = null
    private val transcripts = TranscriptFactsCache()
    private val history = HistoryRowsCache()

    override fun scan(roots: List<SessionHistoryRoot>): SessionHistoryScan {
        val requested = roots.toList()
        while (true) {
            var owner = false
            val refresh = synchronized(monitor) {
                val now = clock()
                cached?.takeIf { it.roots == requested && now - it.at in 0 until CACHE_MS }?.let { return it.scan }
                refreshing ?: Refresh(requested, CompletableFuture()).also {
                    refreshing = it
                    owner = true
                }
            }
            if (owner) return read(refresh)
            val result = await(refresh)
            if (refresh.roots == requested) return result
        }
    }

    /** Only the refresh owner mutates passes. Readers wait without holding the publication monitor. */
    private fun read(refresh: Refresh): SessionHistoryScan =
        Cancellables.withCleanup(cleanup = { incomplete(refresh) }) {
            val attempt = Cancellables.runCatchingBestEffort { readPass(refresh.roots) }
            attempt.exceptionOrNull()?.let { failed(refresh, it) }
            val scan = attempt.getOrThrow()
            val retained = cacheable(scan)
            synchronized(monitor) {
                cached = if (retained) Cached(refresh.roots, clock(), scan) else null
                refresh.result.complete(scan)
                refreshing = null
            }
            scan
        }

    private fun readPass(roots: List<SessionHistoryRoot>): SessionHistoryScan {
        val transcriptPass = transcripts.pass()
        val historyPass = history.pass()
        val scan = HistoryScanBuilder(roots, transcriptPass, historyPass).read()
        transcriptPass.commit()
        historyPass.commit()
        return scan
    }

    private fun incomplete(refresh: Refresh) {
        synchronized(monitor) {
            if (refreshing === refresh) {
                refresh.result.completeExceptionally(CancellationException("history refresh did not finish"))
                refreshing = null
            }
        }
    }

    private fun failed(refresh: Refresh, error: Throwable) {
        synchronized(monitor) {
            refresh.result.completeExceptionally(error)
            refreshing = null
        }
    }

    private fun await(refresh: Refresh): SessionHistoryScan = try {
        refresh.result.get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    }

    /** Large results remain complete for this call and overlapping callers, but are not retained as a cache. */
    private fun cacheable(scan: SessionHistoryScan): Boolean {
        if (scan.sessions.size > MAX_SCAN_ENTRIES) return false
        var bytes = 0L
        val estimates = scan.sessions.asSequence().map(::entryBytes) +
            scan.errors.asSequence().map { SCAN_ERROR_BYTES + 2L * it.length }
        for (size in estimates) {
            bytes += size
            if (bytes > MAX_SCAN_BYTES) return false
        }
        return true
    }

    private fun entryBytes(entry: SessionHistoryEntry): Long {
        val characters = entry.sessionId.length + (entry.name?.length ?: 0) +
            (entry.project?.length ?: 0) + (entry.head?.length ?: 0)
        return SCAN_ENTRY_BYTES + 2L * characters
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
        return SessionHistoryScan(entries, state.errors.toList())
    }

    private fun history(root: SessionHistoryRoot) {
        val file = root.dir.resolve("history.jsonl")
        try {
            historyRows.rows(file) { prompt(it, root.head) }
        } catch (_: NoSuchFileException) {
            // This config tree has no history file; its primary transcripts still count.
        } catch (error: IOException) {
            state.errors += "$file: ${SafeFailureText.render(error)}"
        }
    }

    private fun prompt(row: HistoryRow, head: String?) {
        val facts = sessions.getOrPut(row.id) { HistoryFacts(row.id) }
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
