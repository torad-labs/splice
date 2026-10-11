// NEW: Oct 11, 2026 — the parked compaction summaries as a kept store Settings > Your data counts and clears: one
// directory per head under `compactions/<head>`, including a head that has left splice.toml. Each summary is held
// two hours for Claude Code's retry and swept at its head's start or next save; this counts what waits for that and
// clears it before. A head writing at that moment loses a summary that its next retry would have fetched, which is
// what a delete now asks for.
package splice.app.control.kept

import splice.core.config.StatePaths
import splice.core.util.AgedFiles
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

internal class CompactionSummariesKept(private val paths: StatePaths) : KeptStore {
    override fun held(): StoreHeld {
        val counts = stores().map { it.census() }
        return StoreHeld(
            counts.sumOf { it.files },
            counts.sumOf { it.bytes },
            counts.mapNotNull { it.oldestMs }.minOrNull(),
        )
    }

    override fun clear(): String? =
        Cancellables.runCatchingCancellable { stores().forEach { it.deleteBefore(Long.MAX_VALUE) } }.exceptionOrNull()
            ?.let { "compaction summaries: ${SafeFailureText.render(it)}" }

    private fun stores(): List<AgedFiles> {
        val root: Path = paths.compactionsDir
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(
            root,
        ).use { entries -> entries.filter { Files.isDirectory(it) }.map { AgedFiles(it) }.toList() }
    }
}
