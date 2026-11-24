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
        if (parent != null) {
            rejectSymlinkDir(parent, "a peer's state folder parent")
            if (parent.fileName?.toString() == "peers") {
                val parentOpen = SecureFile.ownerOnlyDirectory(parent)
                if (parentOpen != null) {
                    throw IOException("a peer's state folder parent is not owner-only: $parentOpen")
                }
            }
        }
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
        if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) enforce0600(live)
        Files.move(live, rolled, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        enforce0600(rolled)
        fresh(live)
    }

    /** [value] as the whole content of [file], replaced atomically. */
    fun writeNumber(file: Path, value: Long) {
        prepare()
        rejectSymlinkFile(file)
        SecureFile.writeAtomic0600(file, value.toString())
    }

    private fun fresh(file: Path) {
        val lockFile = file.resolveSibling("${file.fileName}.lock")
        val rolledFile = dir.resolve(PEER_JOURNAL_ROLLED_FILE)
        rejectSymlinkFile(file)
        rejectSymlinkFile(lockFile)
        rejectSymlinkFile(rolledFile)
        if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) enforce0600(lockFile)
        if (Files.exists(rolledFile, LinkOption.NOFOLLOW_LINKS)) enforce0600(rolledFile)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            enforce0600(file)
            return
        }
        try {
            SecureFile.createNew0600(file, ByteArray(0))
        } catch (_: FileAlreadyExistsException) {
            rejectSymlinkFile(file)
            enforce0600(file)
        }
    }

    private fun enforce0600(path: Path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("a peer's journal path is not a regular file: $path")
        }
        if (SecureFile.CREATE_0600 == null) return
        val refusal = SecureFile.set0600(path)
        if (refusal != null) throw IOException("a peer's journal file could not be tightened to 0600: $refusal")
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
