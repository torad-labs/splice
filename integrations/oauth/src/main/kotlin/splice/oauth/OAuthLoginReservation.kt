// NEW: v0.4.0 SF01 — cross-process ordinal ownership for Kimi device login.
package splice.oauth

import splice.core.topology.AuthKind
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/** Leases automatic and explicit labels without reading or writing credential contents. */
internal class OAuthLoginReservation {
    fun reserveOrdinal(kind: AuthKind.OAuth, poolDir: Path): Lease {
        val locks = Files.createDirectories(poolDir.resolve(".login-locks"))
        val base = kind.wire.removeSuffix("-oauth")
        var ordinal = 2
        while (true) {
            val label = "$base-$ordinal"
            tryLease(locks.resolve("$label.lock"), poolDir, label, rejectOccupied = true)?.let { return it }
            ordinal += 1
        }
    }

    fun reserveLabel(poolDir: Path, label: String): Lease {
        val locks = Files.createDirectories(poolDir.resolve(".login-locks"))
        return tryLease(locks.resolve("$label.lock"), poolDir, label, rejectOccupied = false)
            ?: throw OAuthAccountRefused("OAuth account label already has a login in progress")
    }

    private fun tryLease(lockFile: Path, poolDir: Path, label: String, rejectOccupied: Boolean): Lease? {
        val channel = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        var retained = false
        try {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: return null
            if (rejectOccupied && occupied(poolDir, label)) return null
            retained = true
            return Lease(label, channel, lock)
        } finally {
            if (!retained) channel.close()
        }
    }

    private fun occupied(poolDir: Path, label: String): Boolean =
        Files.exists(poolDir.resolve("$label.json"), LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(poolDir.resolve("$label-quota.json"), LinkOption.NOFOLLOW_LINKS)

    /** The lock file remains as a stable inode; close releases only the OS lock and channel. */
    internal class Lease(
        val label: String,
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }
}
