// NEW: Oct 10, 2026 — the stores a history cut reaches that are not turn records: transcript copies, reasoning
// kept between turns and code mode work, counted when the read is drawn and cleared by the same save.
//
// Every one is cut by its last activity, never by whether it could still be resumed (Marlin, Oct 10): nearly
// everything is resumable while its file exists, so that exception would keep months of data after "Today only".
// A transcript copy is aged by the live transcript it was copied from, and the other two by their files' last
// write. Counting and clearing share one place so the figure the person confirmed is the figure that is removed.
package splice.app.control.mount

import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.CODE_MODE_DIR
import splice.core.config.REASONING_DIR
import splice.core.config.StatePaths
import splice.core.util.AgedFiles
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.head.perf.HeldStore
import splice.head.perf.HeldStores
import java.nio.file.Files
import java.nio.file.Path

private const val TRANSCRIPT_COPIES = "transcript_copies"
private const val COMPACTION_SUMMARIES = "compaction_summaries"

internal class KeptHistoryStores(private val paths: StatePaths) {

    /** What each store holds from before the cut, and why one could not be counted, so a total is never taken
     *  from a count that was not whole. */
    fun heldBefore(momentMs: Long): HeldStores {
        val failed = mutableListOf<String>()
        val counted = mutableListOf<HeldStore>()
        val copies = Cancellables.runCatchingCancellable { TranscriptOriginals(paths).heldBefore(momentMs) }
        copies.onSuccess { counted += HeldStore(TRANSCRIPT_COPIES, it.bytes, it.files) }
        copies.onFailure { failed += "$TRANSCRIPT_COPIES: ${SafeFailureText.render(it)}" }
        val listed = Cancellables.runCatchingCancellable { filed() }.getOrElse {
            return HeldStores(counted, "the kept stores could not be listed: ${SafeFailureText.render(it)}")
        }
        for ((key, dirs) in listed) {
            val bytes = dirs.sumOf { dir ->
                Cancellables.runCatchingCancellable { dir.bytesBefore(momentMs) }.getOrElse {
                    failed += "$key: ${SafeFailureText.render(it)}"
                    0L
                }
            }
            counted += HeldStore(key, bytes)
        }
        return HeldStores(counted, failed.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    /** Every compaction summary held, of any age, or why it could not be counted. */
    fun summariesHeld(): HeldStores {
        val bytes = Cancellables.runCatchingCancellable { summaries().sumOf { it.bytesBefore(Long.MAX_VALUE) } }
        return bytes.fold(
            { HeldStores(listOf(HeldStore(COMPACTION_SUMMARIES, it)), null) },
            { HeldStores(emptyList(), "$COMPACTION_SUMMARIES: ${SafeFailureText.render(it)}") },
        )
    }

    /** Clears what the cut reaches, or names the first store that could not be cleared. */
    fun trimBefore(momentMs: Long): String? =
        copiesCleared(momentMs) ?: summariesCleared(momentMs) ?: filedCleared(momentMs)

    private fun copiesCleared(momentMs: Long): String? =
        Cancellables.runCatchingCancellable { TranscriptOriginals(paths).deleteBefore(momentMs) }
            .exceptionOrNull()?.let { "$TRANSCRIPT_COPIES: ${SafeFailureText.render(it)}" }

    private fun summariesCleared(momentMs: Long): String? =
        Cancellables.runCatchingCancellable { summaries().forEach { it.deleteBefore(momentMs) } }
            .exceptionOrNull()?.let { "$COMPACTION_SUMMARIES: ${SafeFailureText.render(it)}" }

    private fun filedCleared(momentMs: Long): String? {
        val listed = Cancellables.runCatchingCancellable { filed() }
            .getOrElse { return "the kept stores: ${SafeFailureText.render(it)}" }
        return listed.firstNotNullOfOrNull { (key, dirs) ->
            dirs.firstNotNullOfOrNull { dir ->
                Cancellables.runCatchingCancellable { dir.deleteBefore(momentMs) }.exceptionOrNull()?.let {
                    "$key: ${SafeFailureText.render(it)}"
                }
            }
        }
    }

    /** Each head's parked compaction summaries (`compactions/<head>`), including a head that has left splice.toml. */
    private fun summaries(): List<AgedFiles> {
        val root = paths.compactionsDir
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { entries ->
            entries.filter { Files.isDirectory(it) }.map { AgedFiles(it) }.toList()
        }
    }

    /** The stores of plain files, by the name the page owns the words for. */
    private fun filed(): Map<String, List<AgedFiles>> = mapOf(
        "reasoning" to headDirs(REASONING_DIR).map { AgedFiles(it) },
        "code_mode" to headDirs(CODE_MODE_DIR).map { AgedFiles(it) },
    )

    /** Every head's directory of one kind (`heads/<key>/<leaf>`), including a head that has left splice.toml: its
     *  files are still on the disk, which is what Your data counts. A kind no head ever wrote is none. */
    private fun headDirs(leaf: String): List<Path> {
        val heads = paths.headsDir
        if (!Files.isDirectory(heads)) return emptyList()
        return Files.list(heads).use { entries ->
            entries.map { it.resolve(leaf) }.filter { Files.isDirectory(it) }.toList()
        }
    }
}
