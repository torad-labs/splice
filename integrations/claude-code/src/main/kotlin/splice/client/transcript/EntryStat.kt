// NEW: Oct 12, 2026 (splice-lead) — one stat of a file, folder or link: what it is, how big, when it changed, and which file it
// is. The history index lists thousands of entries and asks this of each, so it is one system call each.
package splice.client.transcript

import splice.core.util.FileIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: the names one stat is asked for; the unix view adds device and inode in the same call.
private const val NAMES = "size,lastModifiedTime,isRegularFile,isDirectory,isSymbolicLink"

internal class EntryStat(file: Path, followLinks: Boolean = true) {
    private val view = if ("unix" in file.fileSystem.supportedFileAttributeViews()) "unix:dev,ino," else "basic:"
    private val attributes: Map<String, Any?> = if (followLinks) {
        Files.readAttributes(file, view + NAMES)
    } else {
        Files.readAttributes(file, view + NAMES, LinkOption.NOFOLLOW_LINKS)
    }

    val size: Long = (attributes["size"] as? Long) ?: 0L
    val modifiedMs: Long = (attributes["lastModifiedTime"] as? FileTime)?.toMillis() ?: 0L
    val isRegularFile: Boolean = attributes["isRegularFile"] == true
    val isDirectory: Boolean = attributes["isDirectory"] == true
    val isSymbolicLink: Boolean = attributes["isSymbolicLink"] == true

    /** Device and inode where the platform has them: a file replaced under the same name is not the same file. */
    val identity: FileIdentity? = (attributes["dev"] as? Long)?.let { dev ->
        (attributes["ino"] as? Long)?.let { FileIdentity(dev, it) }
    }
}
