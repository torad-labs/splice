package splice.app.mods.run

import splice.app.mods.install.ModPrivateDir
import splice.core.util.Cancellables
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val MOD_START_POLL_MS = 25L // why: a lock held by another process has no wake-up, so it is polled

// The in-process half of every turn, one per lock file. A file lock belongs to the process, and closing any descriptor
// for the file frees it (DaemonLock, DR-162), so threads of this process queue here and only the holder opens the file.
private val inProcess = ConcurrentHashMap<Path, Semaphore>()

/**
 * One mod's turn to read or change its run record and to start its Bun. Whoever holds the turn checks that no earlier
 * run is alive, starts, and records, so a start that arrives late finds the newer run and refuses instead of writing
 * over its record. The turn is held across processes, because the daemon being replaced can still be starting a mod
 * while its successor starts the same one. A semaphore rather than a monitor: a turn is held across suspension and
 * may be released on another thread.
 */
internal class ModStartLocks(private val directory: Path) {
    /** The turn for [name], or null when another holder kept it for [waitMs]. */
    fun acquire(name: String, waitMs: Long): ModStartTurn? {
        val file = directory.resolve("$name.lock").toAbsolutePath().normalize()
        val mine = inProcess.computeIfAbsent(file) { Semaphore(1) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        if (!mine.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return null
        var lock: FileLock? = null
        try {
            lock = lockFile(file, deadline)
        } finally {
            if (lock == null) mine.release()
        }
        return lock?.let { ModStartTurn(mine, it) }
    }

    private fun lockFile(file: Path, deadline: Long): FileLock? {
        ModPrivateDir.create(directory)
        val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        var lock: FileLock? = null
        try {
            lock = channel.tryLock()
            while (lock == null && System.nanoTime() < deadline) {
                Thread.sleep(MOD_START_POLL_MS)
                lock = channel.tryLock()
            }
        } finally {
            if (lock == null) channel.close()
        }
        return lock
    }
}

internal class ModStartTurn(private val mine: Semaphore, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        try {
            // Closing the channel frees the file lock.
            Cancellables.discard(
                Cancellables.runCatchingCancellable { lock.channel().close() },
                "the turn is over; a channel that will not close has nothing left to protect",
            )
        } finally {
            mine.release()
        }
    }
}
