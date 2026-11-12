// NEW: V4-127 review — the MEASUREMENT behind the doctor report's `state_dir_usage` (FEATURES.md §6:
// "Doctor reports the state dir size and the oldest file"). DoctorReportShape.stateDirUsage shapes
// the numbers and says the walk is not its job; nothing did the walk, so the key never reached the
// report. This file is that walk, and nothing else.
//
// WHAT IS COUNTED: every REGULAR file under the state dir, recursively, by its own size and mtime.
// Links are not followed — a link out of the state dir points at somebody else's bytes — and are not
// counted either. An entry the walk cannot read is COUNTED AS UNREADABLE rather than skipped: a
// footprint that silently left out what it could not see would report a smaller, confident number,
// and an unreadable state dir root would read as an empty one. Proven absence of the root is the one
// empty answer.
package splice.diagnostics.doctor.report

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** One walk's result, in the units DoctorReportShape.stateDirUsage takes. */
internal data class StateDirUsage(
    val sizeBytes: Long,
    val fileCount: Int,
    val oldest: StateDirFile?,
    val unreadable: Int,
)

internal class DoctorStateDirScan(private val root: Path) {

    /** Walks [root] as of [nowMs]; an absent root is the empty footprint. The root is resolved first,
     *  so a state dir that is itself a link (a state root moved to another disk) is walked where it
     *  lives — only links INSIDE it are left unfollowed. */
    fun scan(nowMs: Long): StateDirUsage {
        val real = try {
            root.toRealPath()
        } catch (ignored: NoSuchFileException) {
            return StateDirUsage(sizeBytes = 0L, fileCount = 0, oldest = null, unreadable = 0)
        } catch (ignored: IOException) {
            // The root exists but cannot be resolved (a parent without search permission): one
            // unreadable entry, never the empty footprint an absent dir reports.
            return StateDirUsage(sizeBytes = 0L, fileCount = 0, oldest = null, unreadable = 1)
        }
        val tally = Tally(real, nowMs)
        Files.walkFileTree(real, tally)
        return tally.usage()
    }

    private class Tally(private val root: Path, private val nowMs: Long) : SimpleFileVisitor<Path>() {
        private var size = 0L
        private var count = 0
        private var unreadable = 0
        private var oldestName: String? = null
        private var oldestMtime = Long.MAX_VALUE

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile) {
                size += attrs.size()
                count += 1
                val mtime = attrs.lastModifiedTime().toMillis()
                if (mtime < oldestMtime) {
                    oldestMtime = mtime
                    oldestName = root.relativize(file).toString()
                }
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            // Vanished between listing and reading (a rotation, a sweep) is absence, not a fault.
            if (exc !is NoSuchFileException) unreadable += 1
            return FileVisitResult.CONTINUE
        }

        /** The default rethrows a directory's iteration failure and ends the walk; here it is one
         *  more unreadable entry, and the rest of the tree is still counted. */
        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) unreadable += 1
            return FileVisitResult.CONTINUE
        }

        fun usage(): StateDirUsage = StateDirUsage(
            sizeBytes = size,
            fileCount = count,
            oldest = oldestName?.let { StateDirFile(it, (nowMs - oldestMtime).coerceAtLeast(0L)) },
            unreadable = unreadable,
        )
    }
}
