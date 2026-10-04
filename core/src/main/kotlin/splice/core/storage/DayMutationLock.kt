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

/** One stable lock per store, never removed by a day purge, so waiting processes share its inode. */
internal class DayMutationLock(dir: Path, prefix: String) {
    private val file = dir.resolve("$prefix.days.lock")
    private val entered = ThreadLocal.withInitial { false }

    inline fun <T> withLock(action: () -> T): T {
        if (entered.get()) return action()
        if (java.nio.file.Files.isSymbolicLink(file.parent)) {
            throw IOException("day directory is a symlink: ${file.parent}")
        }
        return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            .use { channel ->
                acquire(channel).use {
                    entered.set(true)
                    try {
                        action()
                    } finally {
                        entered.remove()
                    }
                }
            }
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
