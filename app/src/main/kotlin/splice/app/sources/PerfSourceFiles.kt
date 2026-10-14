// NEW: one daemon-owned perf reader per head, shared by turns, economics and budget seeding.
package splice.app.sources

import splice.core.config.StatePaths
import splice.core.memory.HeapCategory
import splice.core.memory.HeapObservation
import splice.core.memory.HeapOwners
import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
import splice.upstream.memory.JvmHeap
import splice.usage.budgets.HeadPerfHistory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

// Covers the 8 KiB digest buffer, bounded parser recycler and scratch, and cache objects with headroom.
internal const val CACHE_OVERHEAD_BYTES = 24L * 1_024

// Covers generation, identity, linked-list and map objects, digest and amortized map capacity.
private const val GENERATION_OVERHEAD_BYTES = 1_024L

// Covers the path's byte/string forms and fallback normalized identity with worst-case UTF-16.
private const val PATH_STORAGE_BYTES_PER_CHAR = 8L

/** A file has one scan owner and one byte-bounded row cache throughout the daemon lifetime.
 *
 *  This is the daemon's per-head turn history, so it also carries how far back that history goes:
 *  the rows, the archive they rotate into and the hourly totals folded from them all answer to the
 *  one window on Settings > Your data. [kept] is read at every use, never captured, so a person who
 *  shortens it has shortened it everywhere with no restart (Marlin, Oct 10, 2026). The default is
 *  the window a fresh install ships with, for a fixture that is not about retention.
 */
internal class PerfSourceFiles(
    private val paths: StatePaths,
    val kept: KeptHistory = KeptHistory { HistoryWindow(HISTORY_DEFAULT_DAYS) },
) : HeadPerfHistory {
    private val sources = ConcurrentHashMap<String, PerfRowsFileSource>()

    override fun rowsFor(head: String): PerfRowsFileSource = sources.computeIfAbsent(head) {
        PerfRowsFileSource(paths.perfStatsFile(it), paths.perfArchiveDir)
    }
}

/** Source-cache overhead estimates and their uncharged publication to the process ledger.
 *  The cache supplies its existing retained-byte totals after mutations; no rows are scanned here.
 *  Cleaner registration keeps only the observation ticket, never a reference to the cache. */
internal object PerfCacheFootprint {
    fun generationBytes(path: Path): Long =
        GENERATION_OVERHEAD_BYTES + path.toString().length * PATH_STORAGE_BYTES_PER_CHAR

    fun observe(owner: PerfRowsCache): HeapObservation = HeapOwners.observe(
        owner,
        JvmHeap.budget,
        CACHE_OVERHEAD_BYTES,
        HeapCategory.RECENT_HISTORY,
    )
}
