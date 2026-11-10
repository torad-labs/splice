// NEW: Oct 11, 2026 — code mode work as a kept store Settings > Your data counts and clears: each head's conversations
// under `heads/<key>/code-mode`, and the single per-head file older versions wrote beside the state dir. A head that
// has left splice.toml is counted too, since its files are still on the disk. A conversation's files are gone once it
// has sat unused past its head's code-mode ttl (24 hours by default): its records by the registry's sweep, its history
// journal by the history sweep (Nov 9, 2026; until then nothing removed a journal while code mode was on). This is the
// count of what waits for that, and the clear before it. The clear leaves the files of conversations a running head
// holds now ([inUse]): removing them under it would break that conversation at its next restart.
package splice.app.control.kept

import splice.core.config.CODE_MODE_DIR
import splice.core.config.CODE_MODE_STATE_SUFFIX
import splice.core.config.StatePaths
import splice.core.util.AgedFiles
import splice.core.util.Cancellables
import splice.core.util.FileStamp
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

internal class CodeModeKept(
    private val paths: StatePaths,
    private val inUse: CodeModeFilesInUse = CodeModeFilesInUse { emptySet() },
) : KeptStore {
    override fun held(): StoreHeld {
        val counts = stores().map { it.census() }
        return StoreHeld(
            counts.sumOf { it.files },
            counts.sumOf { it.bytes },
            counts.mapNotNull { it.oldestMs }.minOrNull(),
        )
    }

    override fun clear(): String? =
        Cancellables.runCatchingCancellable {
            stores(InUseFileStamp(inUse())).forEach { it.deleteBefore(Long.MAX_VALUE) }
        }.exceptionOrNull()?.let { "code mode work: ${SafeFailureText.render(it)}" }

    /** Each head's conversation directory, and each head's older single file, aged by [stamp] when one is given. */
    private fun stores(stamp: FileStamp? = null): List<AgedFiles> =
        (conversations() + singles()).map { if (stamp == null) AgedFiles(it) else AgedFiles(it, stamp) }

    private fun conversations(): List<Path> = children(paths.headsDir).map { it.resolve(CODE_MODE_DIR) }
        .filter { Files.isDirectory(it) }

    private fun singles(): List<Path> = children(paths.stateDir).filter {
        it.fileName.toString().endsWith(CODE_MODE_STATE_SUFFIX)
    }

    private fun children(dir: Path): List<Path> =
        if (Files.isDirectory(dir)) Files.list(dir).use { it.toList() } else emptyList()
}
