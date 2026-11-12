// NEW: the filesystem questions whose complete answer to "it cannot be read" is "nothing there". Each names the
// failures it takes for that answer: IOException from the filesystem, UncheckedIOException from a listing that fails
// part-way, InvalidPathException from text no path can spell. Anything else, and cancellation, still propagates.
// Callers that must tell absent from unreadable ask the filesystem directly instead.
package splice.core.util

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.attribute.FileTime

public object PathProbe {
    /** [path] with its links resolved; null when it is absent or unreadable. */
    public fun resolved(path: Path): Path? = try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }

    /** [raw] as a path; null for text the filesystem cannot spell (a NUL byte). */
    public fun spelled(raw: String): Path? = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        null
    }

    /** Where the link at [link] points; null when it is absent, unreadable, or not a link. */
    public fun linkTarget(link: Path): Path? = try {
        Files.readSymbolicLink(link)
    } catch (_: IOException) {
        null
    }

    /** The whole text of [file]; null when it cannot be read. */
    public fun text(file: Path): String? = try {
        Files.readString(file)
    } catch (_: IOException) {
        null
    }

    /** The entries of [dir]; empty when it is absent or unreadable, including when it fails while being listed. */
    public fun entries(dir: Path): List<Path> = try {
        Files.list(dir).use { it.toList() }
    } catch (_: IOException) {
        emptyList()
    } catch (_: UncheckedIOException) {
        emptyList()
    }

    /** When [path] last changed; null when it does not exist or cannot be read. */
    public fun modified(path: Path): FileTime? = try {
        Files.getLastModifiedTime(path)
    } catch (_: IOException) {
        null
    }
}
