// NEW: V4-286 — one listing of a state directory that tells absence from failure. The day-file stores
// and a head's kept files each listed with every failure read as "no files", so a directory that
// existed and could not be listed said "nothing to purge", deleted nothing at a start and logged
// nothing. A DirectoryIteratorException, the unchecked wrapper a stream throws when an entry fails
// partway, escaped every catch and stopped the daemon's start.
package splice.core.storage

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

public object DirectoryEntries {
    /** Every entry of [dir], none when [dir] does not exist. A directory that exists and cannot be
     *  listed, or fails partway through, throws the IOException that says why: never an empty one. */
    @Throws(IOException::class)
    public fun of(dir: Path): List<Path> =
        try {
            Files.newDirectoryStream(dir).use { it.toList() }
        } catch (_: NoSuchFileException) {
            emptyList()
        } catch (partway: DirectoryIteratorException) {
            throw partway.cause ?: IOException(partway)
        }
}
