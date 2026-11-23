// NEW: desktop-3p-parity (Nov 23, 2026). A folder copied whole into a Claude Desktop profile (an extension, a skill),
// so it works there as it does where it came from: each file keeps its permission bits, the executable bit of a
// bundled binary or script included (a copy that lost it left extensions that could not start), with set-id bits
// never carried; a link is kept when it is relative and stays inside the folder, and dropped when it points out of it,
// so a copy never reaches anything the folder did not hold. The copy is bounded, is made beside the target and moved
// into place whole, and never lands over anything already there: a folder too large to copy whole is not copied.
package splice.app.desktop.setup

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class DesktopTreeCopy(
    private val maxDepth: Int = TREE_MAX_DEPTH,
    private val maxEntries: Int = TREE_MAX_ENTRIES,
) {
    /** [source], a real folder, copied to [target], which must not exist yet; whether it was. */
    fun copy(source: Path, target: Path): Boolean {
        val parent = target.toAbsolutePath().parent
        val copyable = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
            parent != null && !Files.isSymbolicLink(parent)
        if (!copyable) return false
        Files.createDirectories(parent)
        val staging = parent.resolve(".${target.fileName}$STAGING_SUFFIX")
        removeTree(staging)
        val whole = try {
            copyInto(source.toAbsolutePath().normalize(), staging)
        } catch (failure: IOException) {
            removeTree(staging)
            throw failure
        }
        if (whole) Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE) else removeTree(staging)
        return whole
    }

    /** Every entry of [source] copied under [staging]; false when the tree is deeper or larger than a copy takes. */
    private fun copyInto(source: Path, staging: Path): Boolean {
        val root = source.toRealPath()
        val folders = mutableListOf<Pair<Path, Path>>()
        var count = 0
        var whole = true
        Files.walk(source).use { stream ->
            val entries = stream.iterator()
            while (whole && entries.hasNext()) {
                val entry = entries.next()
                val relative = source.relativize(entry)
                whole = relative.nameCount <= maxDepth && count++ < maxEntries
                val to = staging.resolve(relative.toString())
                if (whole && copyOne(entry, root, to)) folders += entry to to
            }
        }
        // A folder's own bits last, once everything is in it: a read-only folder takes nothing after its bits are set.
        folders.asReversed().forEach { (from, to) -> keepBits(from, to) }
        return whole
    }

    /** [entry] copied to [to]; whether it was a folder, whose bits are set once its entries are in. */
    private fun copyOne(entry: Path, root: Path, to: Path): Boolean {
        when {
            Files.isSymbolicLink(entry) -> inside(entry, root)?.let { Files.createSymbolicLink(to, it) }
            Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(to)
            Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> {
                Files.copy(entry, to, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                keepBits(entry, to)
            }
        }
        return Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
    }

    /** [from]'s nine permission bits set on [to], which clears set-user-ID, set-group-ID and sticky; nothing where the
     *  file system keeps no POSIX permissions. */
    private fun keepBits(from: Path, to: Path) {
        if (TREE_POSIX_VIEW in to.fileSystem.supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from, LinkOption.NOFOLLOW_LINKS))
        }
    }

    /** [link]'s target, when it is relative and what it names, followed to the end, is inside [root]. */
    private fun inside(link: Path, root: Path): Path? {
        val named = Files.readSymbolicLink(link)
        return try {
            named.takeIf { !it.isAbsolute && link.parent.resolve(it).toRealPath().startsWith(root) }
        } catch (_: IOException) {
            null
        }
    }

    /** [dir] and everything in it taken away, when it is there; links are taken away, never followed. */
    private fun removeTree(dir: Path) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

// The suffix of the folder a copy is made in before it is moved into place.
private const val STAGING_SUFFIX = ".splice-copy"
private const val TREE_POSIX_VIEW = "posix"

// why: an extension or skill nests a handful of levels (node_modules reaches about ten); twelve bounds a malformed or
// looping tree without refusing any real one.
private const val TREE_MAX_DEPTH = 12

// why: bounds one folder's copy; a bundled node extension is a few thousand files and folders, and one past this is
// not copied at all rather than copied in part.
private const val TREE_MAX_ENTRIES = 10_000
