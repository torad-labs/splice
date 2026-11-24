// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 2): every write package C makes to disk, in one place. The
// journal and the outbox log append through JsonlSink, the rolled journal and the cursor files are owner-only
// (0600) in an owner-only (0700) folder, and nothing else in the package writes a file.
package splice.sessions.peer.journal

import splice.core.util.JsonlSink
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class PeerJournalFiles(private val dir: Path) {

    /** The peer's state folder, owner-only and made if it is not there; it refuses a symlink or a folder it cannot
     *  close. */
    fun prepare() {
        val parent = dir.parent
        if (parent != null) rejectSymlinkDir(parent, "a peer's state folder parent")
        rejectSymlinkDir(dir, "a peer's state folder")
        val open = SecureFile.ownerOnlyDirectory(dir)
        if (open != null) throw IOException("a peer's state folder is not owner-only: $open")
    }

    /** One line appended to [file], which is made owner-only first; JsonlSink rolls it past [maxBytes]. */
    fun append(file: Path, line: String, maxBytes: Long) {
        prepare()
        fresh(file)
        JsonlSink.appendLine(file, line, maxBytes)
    }

    /** [live] becomes [rolled], replacing the older one, and a new owner-only [live] takes its place. */
    fun roll(live: Path, rolled: Path) {
        prepare()
        rejectSymlinkFile(live)
        rejectSymlinkFile(rolled)
        Files.move(live, rolled, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        fresh(live)
    }

    /** [value] as the whole content of [file], replaced atomically. */
    fun writeNumber(file: Path, value: Long) {
        prepare()
        rejectSymlinkFile(file)
        SecureFile.writeAtomic0600(file, value.toString())
    }

    private fun fresh(file: Path) {
        rejectSymlinkFile(file)
        rejectSymlinkFile(file.resolveSibling("${file.fileName}.lock"))
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("a peer's journal path is not a regular file: $file")
            }
            return
        }
        try {
            SecureFile.createNew0600(file, ByteArray(0))
        } catch (_: FileAlreadyExistsException) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("a peer's journal path is not a regular file: $file")
            }
        }
    }

    private fun rejectSymlinkDir(path: Path, label: String) {
        if (Files.isSymbolicLink(path)) throw IOException("$label is a symbolic link: $path")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("$label is not a directory: $path")
        }
    }

    private fun rejectSymlinkFile(path: Path) {
        if (Files.isSymbolicLink(path)) throw IOException("a peer's journal file is a symbolic link: $path")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("a peer's journal path is not a regular file: $path")
        }
    }
}
