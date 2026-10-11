// NEW: Oct 10, 2026 — the files of one store directory that are older than a moment, counted and removed.
//
// Settings > Your data lists stores it could not count or delete: reasoning kept between turns, code mode
// work, compaction summaries. Each is a directory of files its store writes and reads whole, so "older than
// the cut" is a question about a file's last write, and this answers it for all of them in one place rather
// than three directory walks that drift apart.
//
// A FILE'S LAST WRITE IS ITS LAST ACTIVITY. The reasoning and code-mode files are appended to for as long as
// their conversation lives and the compaction summary is written once, so the newest write is when anyone last
// used it. Resumability is not the test (Marlin, Oct 10): nearly everything is resumable while its file exists.
//
// WHAT A MISSING DIRECTORY MEANS. A store that never wrote has no directory, and that is zero bytes, never a
// failure: an install that does not use code mode must not read "could not count" on a row it does not have.
// A directory that exists and cannot be walked IS a failure and throws, so a count is never a guess.
//
// Only regular files directly under [root] or below it are counted; a symbolic link is neither followed nor
// removed, because a cut that deleted what a link pointed at would delete outside the store.
package splice.core.util

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** When a file was last used, in epoch milliseconds. */
public fun interface FileStamp {
    public operator fun invoke(file: Path): Long
}

/** The files under [root] last written before a moment. [stamp] defaults to the file's own last write. */
public class AgedFiles(
    private val root: Path,
    private val stamp: FileStamp = FileStamp { Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis() },
) {
    /** How many files [root] holds, their bytes, and the oldest last write (null when it holds none). */
    public fun census(): Census {
        val files = older(Long.MAX_VALUE)
        return Census(files.size.toLong(), files.sumOf { Files.size(it) }, files.minOfOrNull { stamp(it) })
    }

    /** What [census] counts. */
    public data class Census(val files: Long, val bytes: Long, val oldestMs: Long?)

    /** Bytes held in files last written before [momentMs]. */
    public fun bytesBefore(momentMs: Long): Long = older(momentMs).sumOf { Files.size(it) }

    /** Removes every file last written before [momentMs] and returns the bytes it freed. A file that is gone by
     *  the time it is reached is already what was asked for, so it is not a failure. */
    public fun deleteBefore(momentMs: Long): Long {
        var freed = 0L
        for (file in older(momentMs)) {
            val size = try {
                Files.size(file)
            } catch (_: NoSuchFileException) {
                continue
            }
            try {
                Files.delete(file)
                freed += size
            } catch (_: NoSuchFileException) {
                // Removed by its own store between the walk and now.
            }
        }
        return freed
    }

    private fun older(momentMs: Long): List<Path> {
        try {
            Files.walk(root).use { walk ->
                return walk
                    .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .filter { !it.fileName.toString().startsWith(".") }
                    .filter { stamp(it) < momentMs }
                    .toList()
            }
        } catch (_: NoSuchFileException) {
            return emptyList()
        } catch (failure: IOException) {
            throw IOException("cannot read $root: ${SafeFailureText.render(failure)}", failure)
        }
    }
}
