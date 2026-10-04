// NEW: persistence failures query available disk bytes without turning an unreadable store into a full disk.
package splice.provider.codex.state.save

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Available bytes on the disk holding a path, or null when no usable measurement exists. */
internal fun interface StateDiskSpace {
    operator fun invoke(file: Path): Long?

    object Usable : StateDiskSpace {
        override fun invoke(file: Path): Long? {
            val existing = generateSequence(file.toAbsolutePath()) { it.parent }.firstOrNull { Files.exists(it) }
            return try {
                existing?.let { Files.getFileStore(it).usableSpace }
            } catch (_: IOException) {
                null
            }
        }
    }
}
