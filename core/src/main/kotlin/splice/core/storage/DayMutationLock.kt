// NEW: V4-457 bounded cross-process exclusion for day index, companion and purge mutations.
package splice.core.storage

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

// why: day telemetry cannot park the shared file lane behind a wedged process indefinitely.
private const val DAY_MUTATION_WAIT_MS = 1_000L

// why: a ten-millisecond poll bounds same-JVM and cross-process contention overhead.
private const val DAY_MUTATION_POLL_MS = 10L

// Shared only by lock instances entering on the same thread; empty ownership retains no directory paths.
private val enteredDayLocks = ThreadLocal.withInitial { HashSet<Path>() }

/** One operation published under the directory-wide day mutation fence. */
public fun interface DayDirectoryAction<T> {
    public operator fun invoke(): T
}

/** A shared body budget takes this fence before every per-head lock and keeps it through index publication. */
internal class DayDirectoryLock(dir: Path) {
    private val shared = DayMutationLock(dir, "directory", directory = null)

    public fun <T> withLock(action: DayDirectoryAction<T>): T = shared.withLock { action() }
}

/** Stable directory and store locks, never removed by a day purge, so waiting processes share their inode. */
internal class DayMutationLock(
    dir: Path,
    prefix: String,
    private val directory: DayDirectoryLock? = DayDirectoryLock(dir),
) {
    private val file = dir.toAbsolutePath().normalize().resolve("$prefix.days.lock")

    inline fun <T> withLock(crossinline action: () -> T): T {
        val shared = directory
        return if (shared == null) withStoreLock(action) else shared.withLock { withStoreLock(action) }
    }

    private inline fun <T> withStoreLock(action: () -> T): T {
        val entered = enteredDayLocks.get()
        if (file in entered) return action()
        if (java.nio.file.Files.isSymbolicLink(file.parent)) {
            throw IOException("day directory is a symlink: ${file.parent}")
        }
        return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            .use { channel ->
                acquire(channel).use {
                    entered.add(file)
                    try {
                        action()
                    } finally {
                        leave(entered)
                    }
                }
            }
    }

    private fun leave(entered: MutableSet<Path>) {
        entered.remove(file)
        if (entered.isEmpty()) enteredDayLocks.remove()
    }

    private fun acquire(channel: FileChannel): FileLock {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DAY_MUTATION_WAIT_MS)
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return lock
            if (System.nanoTime() >= deadline) throw IOException("day mutation lock timed out: $file")
            Thread.sleep(DAY_MUTATION_POLL_MS)
        }
    }
}
