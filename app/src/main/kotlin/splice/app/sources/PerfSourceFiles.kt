// NEW: one daemon-owned perf reader per head, shared by turns, economics and budget seeding.
package splice.app.sources

import splice.core.config.StatePaths
import splice.usage.budgets.HeadPerfHistory
import java.util.concurrent.ConcurrentHashMap

/** A file has one scan owner and one byte-bounded row cache throughout the daemon lifetime. */
internal class PerfSourceFiles(private val paths: StatePaths) : HeadPerfHistory {
    private val sources = ConcurrentHashMap<String, PerfRowsFileSource>()

    override fun rowsFor(head: String): PerfRowsFileSource = sources.computeIfAbsent(head) {
        PerfRowsFileSource(paths.perfStatsFile(it), paths.perfArchiveDir)
    }
}
