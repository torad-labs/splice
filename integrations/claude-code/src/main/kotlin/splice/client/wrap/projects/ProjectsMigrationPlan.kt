// NEW: the projects migration's preflight, out of ProjectsLink (concentration, 2026-10-27). It walks a head's
// projects tree against the global one and decides every link before the first is made. Divergences A to C are
// described in ProjectsLink's header.
package splice.client.wrap.projects

import splice.client.wrap.ProjectsFs
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

/** Everything the preflight decided before the first link: the global dirs to create, the hardlinks
 *  (global name -> head file) and symlinks (global name -> target string) in walk order with every
 *  collision already redirected to its parked name, and the parked names for the log. A collision parks
 *  under [parkSuffix], the head's `.from-<headKey>`. */
internal class ProjectsMigrationPlan(private val parkSuffix: String) {
    val newGlobalDirs = mutableListOf<Path>()
    val hardLinks = mutableListOf<Pair<Path, Path>>()
    val symLinks = mutableListOf<Pair<Path, Path>>()
    val parked = mutableListOf<Path>()

    /** Dirs first, then every link in order, appending each global name to [created] as it lands so a
     *  failure part-way knows exactly what to unlink. */
    fun apply(fs: ProjectsFs, created: MutableList<Path>) {
        newGlobalDirs.forEach { Files.createDirectories(it) }
        hardLinks.forEach { (link, existing) -> created.add(fs.createLink(link, existing)) }
        symLinks.forEach { (link, target) -> created.add(fs.createSymbolicLink(link, target)) }
    }

    fun parkedLine(globalProjects: Path): String =
        "[projects] ${parked.size} entries already existed in $globalProjects; kept the global copy, " +
            "parked this head's beside it: " +
            parked.joinToString(", ") { globalProjects.relativize(it).toString() } + "\n"

    /** Walk [head] against [global] filling this plan; the first unexpected entry is the decline cause. */
    fun preflight(head: Path, global: Path): String? {
        val entries = Files.newDirectoryStream(head).use { stream ->
            stream.toList().sortedBy { it.fileName.toString() }
        }
        for (entry in entries) {
            val target = global.resolve(entry.fileName)
            val cause = when {
                entry.isSymbolicLink() -> preflightSymlink(entry, target)
                Files.isDirectory(entry, NOFOLLOW_LINKS) -> preflightDir(entry, target)
                Files.isRegularFile(entry, NOFOLLOW_LINKS) -> preflightFile(entry, target)
                else -> "unexpected entry '${entry.fileName}' (neither file, directory nor symlink)"
            }
            if (cause != null) return cause
        }
        return null
    }

    // Divergence A: a directory both sides hold is merged into; an absent global one is created.
    private fun preflightDir(entry: Path, target: Path): String? {
        if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isDirectory(target, NOFOLLOW_LINKS)) {
            return "'${entry.fileName}' is a directory here but not in the global projects"
        }
        if (!Files.exists(target, NOFOLLOW_LINKS)) newGlobalDirs.add(target)
        return preflight(entry, target)
    }

    // Divergence B: the global name gets this file's inode when free; a same-named file that already
    // IS this inode is done; any other collision parks the inode under the head's suffix instead.
    private fun preflightFile(entry: Path, target: Path): String? {
        val parkedName = target.resolveSibling("${target.fileName}$parkSuffix")
        return when {
            !Files.exists(target, NOFOLLOW_LINKS) -> null.also { hardLinks.add(target to entry) }
            Files.isSameFile(target, entry) -> null
            !Files.exists(parkedName, NOFOLLOW_LINKS) -> null.also {
                hardLinks.add(parkedName to entry)
                parked.add(parkedName)
            }
            Files.isSameFile(parkedName, entry) -> null
            else -> "'${target.fileName}' and its parked copy '${parkedName.fileName}' both already exist globally"
        }
    }

    // Divergence C: a symlink is recreated with its target string verbatim; one already there with
    // the identical target is done; a different one parks, like a file.
    private fun preflightSymlink(entry: Path, target: Path): String? {
        val dest = Files.readSymbolicLink(entry)
        val parkedName = target.resolveSibling("${target.fileName}$parkSuffix")
        return when {
            !Files.exists(target, NOFOLLOW_LINKS) -> null.also { symLinks.add(target to dest) }
            sameLink(target, dest) -> null
            !Files.exists(parkedName, NOFOLLOW_LINKS) -> null.also {
                symLinks.add(parkedName to dest)
                parked.add(parkedName)
            }
            sameLink(parkedName, dest) -> null
            else -> "'${target.fileName}' and its parked copy '${parkedName.fileName}' both already exist globally"
        }
    }

    /** Whether [path] is already a symlink to [dest]: the entry the plan skips as done. */
    private fun sameLink(path: Path, dest: Path): Boolean =
        path.isSymbolicLink() && Files.readSymbolicLink(path) == dest
}
