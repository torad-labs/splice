// NEW: V4-445 — updater cleanup can remove the version wrap recorded. Version-directory selection is
// separate from WrappedHead's symlink/state transaction; both reconciliation and unwrap use it.
package splice.client.wrap

import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object WrapBinaryVersions {
    /** Preserve the original target spelling while it exists, otherwise use a surviving version. */
    fun liveTarget(cmd: Path, state: WrapState): Path? {
        val recorded = cmd.resolveSibling(state.shadowedSymlinkTarget)
        if (Files.exists(recorded)) return Paths.get(state.shadowedSymlinkTarget)
        return newestBeside(recorded)
    }

    /** Claude Code keeps executable versions beside one another; numeric order, not name order. */
    fun newestBeside(gone: Path): Path? {
        val dir = gone.parent ?: return null
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-29: an unreadable directory has no newest version; the caller says so
        val entries = Cancellables.runCatchingCancellable { Files.list(dir).use { it.toList() } }.getOrNull().orEmpty()
        return entries.filter { Files.isRegularFile(it) && Files.isExecutable(it) }
            .maxWithOrNull(compareBy<Path, List<Int>>(VersionOrder) { versionParts(it.fileName.toString()) })
    }

    private fun versionParts(name: String): List<Int> = name.split('.').map { it.toIntOrNull() ?: 0 }
}

/** Element-wise order: 2.1.285 follows 2.1.99, and a longer version follows its prefix. */
private object VersionOrder : Comparator<List<Int>> {
    override fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
