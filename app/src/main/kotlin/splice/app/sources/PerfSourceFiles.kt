// NEW: one daemon-owned perf reader per head, shared by turns, economics and budget seeding.
package splice.app.sources

import splice.core.config.StatePaths
import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
import splice.usage.budgets.HeadPerfHistory
import java.util.concurrent.ConcurrentHashMap

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
