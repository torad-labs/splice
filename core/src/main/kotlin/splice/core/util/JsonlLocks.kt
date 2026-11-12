// #525 (2026-11-12): the lock JsonlSink.appendLine holds while it writes, in a home of its own. A daemon that is
// retiring finishes the answers it was giving beside the next one, and both keep records in one directory, so a file
// one process appends to is read whole and replaced whole by the other. Those two hold the appends' lock as well.
package splice.core.util

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** The hold every writer of one line file takes while it writes: this JVM's monitor for the file, and the advisory
 *  lock on its `.lock` sibling that other processes take (IO-001 and DR-178, told at [JsonlSink.appendLine]). */
internal object JsonlLocks {
    val monitors = ConcurrentHashMap<Path, Any>()

    // The `.lock` sibling is made 0600: it holds no byte, and a lock others may open is a lock others may hold.
    private val LOCK_OPTIONS = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)

    /** Run [held] holding [file]'s lock. It is handed null when the cross-process lock was not had within
     *  [LOCK_WAIT_MS]: the caller then goes on without it, each as it says. */
    inline fun <T> holding(file: Path, held: (FileLock?) -> T): T {
        val normalized = file.toAbsolutePath().normalize()
        synchronized(monitors.computeIfAbsent(normalized) { Any() }) {
            channel(normalized).use { channel ->
                val lock = acquire(channel)
                try {
                    return held(lock)
                } finally {
                    lock?.release()
                }
            }
        }
    }

    fun channel(normalized: Path): FileChannel {
        val lockPath = normalized.resolveSibling("${normalized.fileName}$JSONL_LOCK_SUFFIX")
        val mode = SecureFile.CREATE_0600
        if (mode == null) return FileChannel.open(lockPath, LOCK_OPTIONS)
        return FileChannel.open(lockPath, LOCK_OPTIONS, mode)
    }

    /** tryLock poll to [LOCK_WAIT_MS]; null means the budget is spent. A same-JVM overlap throws instead of queueing,
     *  so held-is-held either way — KeyStore.acquireBounded says the same thing about the same primitive. */
    fun acquire(channel: FileChannel): FileLock? {
        val deadline = System.currentTimeMillis() + LOCK_WAIT_MS
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (ignored: OverlappingFileLockException) {
                null // held by this JVM via another channel — same contention, same poll
            }
            if (lock != null) return lock
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(LOCK_POLL_MS)
        }
    }

    // DR-178: three orders of magnitude above a healthy hold (stat + maybe-rename + append) and far
    // below the multi-second parking that harms the lane. Deliberately much tighter than
    // KeyStore's 5s, because what waits here is not one CLI command but every other writer in the
    // process. Poll interval matches KeyStore's.
    private const val LOCK_WAIT_MS = 250L
    private const val LOCK_POLL_MS = 25L
}

/** A line file read or replaced whole while no [JsonlSink.appendLine] of this or another process is inside its write.
 *  When the cross-process lock is not had in time each goes on without it, as an append then appends. */
public object JsonlWhole {
    /** [file]'s bytes. A reader beside a live writer can otherwise see the front of a line still being written, and
     *  take it for a write cut short. */
    public fun read(file: Path): ByteArray = JsonlLocks.holding(file) { Files.readAllBytes(file) }

    /** [contents] put in [file]'s place in one move. A line another process appends while the file is replaced would
     *  otherwise go to the file that is replaced, and be lost with it. */
    public fun replace(file: Path, contents: String) {
        JsonlLocks.holding(file) { SecureFile.writeAtomic0600(file, contents) }
    }
}
