// NEW: V4-344 enumerates Claude Code history and primary transcripts by session id.
package splice.client.transcript

import kotlinx.coroutines.CancellationException
import splice.core.memory.HeapCategory
import splice.core.memory.HeapObservation
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapOwners
import splice.core.memory.HeapRelease
import splice.core.memory.HeapReservations
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
private const val SCAN_CACHE_BYTES = 128L
private const val SCAN_ROOT_BYTES = 128L

/** Claude Code's history.jsonl and primary transcript files, joined by session id. A short cached
 *  snapshot means typing a search does not redo the join on every keystroke, and what each refresh learned from a
 *  file is kept while the file is unchanged: a refresh lists and stats the transcripts and reads only a new or
 *  changed one, and reads only what was appended to retained history files. Caches have entry and byte bounds;
 *  larger scans still return every session and share their result with overlapping readers. */
public class TranscriptHistoryIndex(
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val heap: HeapReservations? = null,
) : SessionHistorySource {
    private data class Cached(
        val roots: List<SessionHistoryRoot>,
        val at: Long,
        val scan: SessionHistoryScan,
        val observations: List<HeapObservation>,
    )
    private data class ScanSize(val sessions: Long, val errors: Long) {
        val bytes: Long get() = sessions + errors
    }
    private data class Refresh(val roots: List<SessionHistoryRoot>, val result: CompletableFuture<SessionHistoryScan>)
    private val monitor = Any()
    private var cached: Cached? = null
    private var refreshing: Refresh? = null
    private val transcripts = TranscriptFactsCache(heap)
    private val history = HistoryRowsCache(heap)

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
            val size = scanSize(scan)
            val observed = size?.let { observeScan(scan, it) } ?: emptyList()
            publish(refresh, scan, size, observed)
            scan
        }

    private fun publish(
        refresh: Refresh,
        scan: SessionHistoryScan,
        size: ScanSize?,
        observed: List<HeapObservation>,
    ) {
        val retained = cacheable(scan, size)
        synchronized(monitor) {
            val reason = if (retained && cached?.roots == refresh.roots) HeapRelease.CLOSED else HeapRelease.EVICTED
            cached?.observations?.forEach { it.close(reason) }
            cached = if (retained) retainedScan(refresh, scan, observed) else null
            refresh.result.complete(scan)
            refreshing = null
        }
    }

    private fun cacheable(scan: SessionHistoryScan, size: ScanSize?): Boolean =
        size != null && scan.sessions.size <= MAX_SCAN_ENTRIES && size.bytes <= MAX_SCAN_BYTES

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

    /** Estimate once at publication. Disabled observation keeps the original oversized-scan short circuit. */
    private fun scanSize(scan: SessionHistoryScan): ScanSize? = if (heap == null) {
        boundedScanSize(scan)
    } else {
        ScanSize(scan.sessions.sumOf(::entryBytes), scan.errors.sumOf { SCAN_ERROR_BYTES + 2L * it.length })
    }

    private fun boundedScanSize(scan: SessionHistoryScan): ScanSize? {
        if (scan.sessions.size > MAX_SCAN_ENTRIES) return null
        var sessions = 0L
        for (entry in scan.sessions) {
            sessions += entryBytes(entry)
            if (sessions > MAX_SCAN_BYTES) return null
        }
        var errors = 0L
        for (error in scan.errors) {
            errors += SCAN_ERROR_BYTES + 2L * error.length
            if (sessions + errors > MAX_SCAN_BYTES) break
        }
        return if (sessions + errors > MAX_SCAN_BYTES) null else ScanSize(sessions, errors)
    }

    private fun observeScan(scan: SessionHistoryScan, size: ScanSize): List<HeapObservation> {
        val source = heap ?: return emptyList()
        // Each returned list has its own Cleaner lifetime, even when the wrapper or cache is gone.
        return buildList {
            if (scan.sessions.isNotEmpty()) {
                add(
                    HeapOwners.observe(
                        scan.sessions,
                        source,
                        size.sessions,
                        HeapCategory.TRANSCRIPT_CACHE,
                        HeapOwnerState.AWAITING_COLLECTION,
                    ),
                )
            }
            if (scan.errors.isNotEmpty()) {
                add(
                    HeapOwners.observe(
                        scan.errors,
                        source,
                        size.errors,
                        HeapCategory.TRANSCRIPT_CACHE,
                        HeapOwnerState.AWAITING_COLLECTION,
                    ),
                )
            }
        }
    }

    private fun retainedScan(
        refresh: Refresh,
        scan: SessionHistoryScan,
        observed: List<HeapObservation>,
    ): Cached {
        val owners = observed.mapTo(ArrayList()) { it.share() }
        heap?.let { source ->
            val size = SCAN_CACHE_BYTES + refresh.roots.sumOf {
                SCAN_ROOT_BYTES + 2L * (it.dir.toString().length + (it.head?.length ?: 0))
            }
            owners += source.observe(size, HeapCategory.TRANSCRIPT_CACHE)
        }
        return Cached(refresh.roots, clock(), scan, owners).also { next ->
            owners.forEach { HeapOwners.keep(next, it) }
        }
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
