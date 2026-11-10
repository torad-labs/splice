// NEW: Nov 9, 2026 — the stamp a clear or a history cut reads code mode's files by, so neither removes a file a
// running head still writes. A conversation's history journal is appended to from what the head holds in memory; with
// the file gone, the next turn wrote a journal that began at its newest revision, and after the next restart every
// turn of that conversation failed. A file in use reads as never old, so no cut reaches it; it goes once its head lets
// it go, by its own sweep or the next clear.
package splice.app.control.kept

import splice.core.util.FileStamp
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** The code-mode files the running heads hold now, asked at each clear or cut. */
internal fun interface CodeModeFilesInUse {
    operator fun invoke(): Set<Path>
}

internal class InUseFileStamp(private val inUse: Set<Path>) : FileStamp {
    override fun invoke(file: Path): Long =
        if (file.toAbsolutePath().normalize() in inUse) {
            Long.MAX_VALUE
        } else {
            Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toMillis()
        }
}
