// NEW: V4-445 — updater cleanup can remove the version wrap recorded. Version-directory selection is
// separate from WrappedHead's symlink/state transaction; both reconciliation and unwrap use it.
package splice.client.wrap

import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object WrapBinaryVersions {
    /** Prefer a newer installed release; otherwise preserve the original target spelling while it exists. */
    fun liveTarget(cmd: Path, state: WrapState): Path? {
        val recorded = cmd.resolveSibling(state.shadowedSymlinkTarget)
        return newerBeside(recorded) ?: if (Files.exists(recorded)) {
            Paths.get(state.shadowedSymlinkTarget)
        } else {
            newestBeside(recorded)
        }
    }

    /** Claude Code keeps executable versions beside one another; numeric order, not name order. Only a
     *  version-named file counts, so a partial download or any other executable there is never chosen. */
    fun newestBeside(gone: Path): Path? {
        val dir = gone.parent ?: return null
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-29: an unreadable directory has no newest version; the caller says so
        val entries = Cancellables.runCatchingCancellable { Files.list(dir).use { it.toList() } }.getOrNull().orEmpty()
        return entries.filter { isVersion(it) && Files.isRegularFile(it) && Files.isExecutable(it) }
            .maxWithOrNull(compareBy<Path, List<Int>>(VersionOrder) { versionParts(it.fileName.toString()) })
    }

    /** A version the updater installed beside [recorded] that is newer than it, or null. The updater
     *  downloads a release beside the running one and keeps the old one (2.1.286 beside 2.1.285,
     *  2026-09-30), so a record moved only when its file was deleted ran the old version for hours. A
     *  [recorded] binary that is not version-named lives outside the updater's directory and stays. */
    fun newerBeside(recorded: Path): Path? {
        if (!isVersion(recorded)) return null
        val newest = newestBeside(recorded) ?: return null
        return newest.takeIf { VersionOrder.compare(versionOf(newest), versionOf(recorded)) > 0 }
    }

    private fun isVersion(path: Path): Boolean = VERSION_NAME.matches(path.fileName?.toString().orEmpty())

    private fun versionOf(path: Path): List<Int> = versionParts(path.fileName.toString())

    private fun versionParts(name: String): List<Int> = name.split('.').map { it.toIntOrNull() ?: 0 }
}

private val VERSION_NAME = Regex("""\d+(\.\d+)+""")

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
