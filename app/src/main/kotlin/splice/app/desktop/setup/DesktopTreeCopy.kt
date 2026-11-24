// NEW: desktop-3p-parity (Nov 23, 2026). A folder copied whole into a Claude Desktop profile (an extension, a skill),
// so it works there as it does where it came from: each file keeps its permission bits, the executable bit of a
// bundled binary or script included (a copy that lost it left extensions that could not start), with set-id bits
// never carried; a link is kept when it is relative and stays inside the folder, and dropped when it points out of it,
// so a copy never reaches anything the folder did not hold. The copy is bounded, is made in a staging folder of its
// own beside the target and moved into place whole, and never lands over anything already there: a folder too large
// to copy whole is not copied, a sync that loses the race to another leaves the other's copy, and a staging folder is
// taken away however the copy ends, read-only folders in it included.
package splice.app.desktop.setup

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

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
        val staging = Files.createTempDirectory(parent, ".${target.fileName}$STAGING_SUFFIX")
        var moved = false
        try {
            moved = copyInto(source.toAbsolutePath().normalize(), staging) && moveInto(staging, target)
        } catch (failure: UncheckedIOException) {
            throw IOException("the copy of $source failed", failure)
        } finally {
            if (!moved) removeTree(staging)
        }
        return moved
    }

    /** [staging] moved to [target]; false when another sync put a copy there first. */
    private fun moveInto(staging: Path, target: Path): Boolean = try {
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: FileAlreadyExistsException) {
        false
    } catch (_: DirectoryNotEmptyException) {
        false
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
                if (whole && copyOne(entry, source, root, to)) folders += entry to to
            }
        }
        // A folder's own bits last, once everything is in it: a read-only folder takes nothing after its bits are set.
        if (whole) folders.asReversed().forEach { (from, to) -> keepBits(from, to) }
        return whole
    }

    /** [entry] copied to [to]; whether it was a folder, whose bits are set once its entries are in. */
    private fun copyOne(entry: Path, source: Path, root: Path, to: Path): Boolean {
        when {
            Files.isSymbolicLink(entry) -> inside(entry, source, root)?.let { Files.createSymbolicLink(to, it) }
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
        if (posix(to)) Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from, LinkOption.NOFOLLOW_LINKS))
    }

    /** [link]'s target, when it is relative, stays inside [source] as written, and what it names, followed to the
     *  end, is inside [root] (the source's real path). */
    private fun inside(link: Path, source: Path, root: Path): Path? {
        val named = Files.readSymbolicLink(link)
        val written = source.relativize(link.parent).resolve(named).normalize()
        val staysIn = !named.isAbsolute && written.firstOrNull()?.toString() != PARENT_DIR
        return try {
            named.takeIf { staysIn && link.parent.resolve(it).toRealPath().startsWith(root) }
        } catch (_: IOException) {
            null
        }
    }

    /** [dir] and everything in it taken away, when it is there; links are taken away, never followed, and a folder
     *  the copy left read-only is made writable by its owner first. */
    private fun removeTree(dir: Path) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
        val entries = Files.walk(dir).use { stream -> stream.toList() }
        entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && posix(it) }.forEach { folder ->
            Files.setPosixFilePermissions(folder, Files.getPosixFilePermissions(folder) + OWNER_ALL)
        }
        entries.asReversed().forEach(Files::delete)
    }

    private fun posix(path: Path): Boolean = TREE_POSIX_VIEW in path.fileSystem.supportedFileAttributeViews()
}

// The suffix of the folder a copy is made in before it is moved into place; the system adds a unique part.
private const val STAGING_SUFFIX = ".splice-copy-"
private const val TREE_POSIX_VIEW = "posix"
private const val PARENT_DIR = ".."

// What the owner needs on a folder to empty it.
private val OWNER_ALL = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)

// why: an extension or skill nests a handful of levels (node_modules reaches about ten); twelve bounds a malformed or
// looping tree without refusing any real one.
private const val TREE_MAX_DEPTH = 12

// why: bounds one folder's copy; a bundled node extension is a few thousand files and folders, and one past this is
// not copied at all rather than copied in part.
private const val TREE_MAX_ENTRIES = 10_000
