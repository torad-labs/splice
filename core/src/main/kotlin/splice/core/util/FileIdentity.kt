// NEW: a file's identity is its device and inode from the unix view, read in the same stat as its other attributes.
package splice.core.util

import java.nio.file.Files
import java.nio.file.Path

/** A file's identity on its filesystem. A file replaced under the same name gets a new inode, so its identity changes. */
public data class FileIdentity(public val dev: Long, public val ino: Long)

/**
 * One stat of [file] for the basic attributes in [names]. Where the platform has the unix view, the same read
 * carries dev and ino, so [identity] and the other attributes describe one moment of the file.
 */
public class FileStat(file: Path, names: String) {
    private val attributes: Map<String, Any?> = Files.readAttributes(
        file,
        (if ("unix" in file.fileSystem.supportedFileAttributeViews()) "unix:dev,ino," else "basic:") + names,
    )

    /** The identity this stat carries, or null where the platform has no unix view. */
    public val identity: FileIdentity?
        get() {
            val dev = attributes["dev"] as? Long ?: return null
            val ino = attributes["ino"] as? Long ?: return null
            return FileIdentity(dev, ino)
        }

    public operator fun get(name: String): Any? = attributes[name]
}
