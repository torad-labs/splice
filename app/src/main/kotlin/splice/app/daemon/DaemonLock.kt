// PORT-OF: the launcher's EADDRINUSE quiet-exit intent @ pre-public-port-baseline, adapted for the single
// daemon (P4-SUP slot): one process binds control_port AND every head port, so the per-port
// trick doesn't compose. A flock on the state dir's daemon.lock (StatePaths.daemonLockFile) is
// the single-flight startup gate — the loser waits briefly, health-checks the winner, exits 0
// LOUD (never a loop).
//
// DR-162 — THE ONE RULE THIS FILE EXISTS TO KEEP: once this process holds the lock, NOTHING in it
// may open another descriptor for that file. A `FileLock` is fcntl on Linux, and POSIX drops every
// lock a process holds on a file the moment ANY descriptor for it is closed — a second
// FileChannel, a plain `Files.readString`, anything. The JVM does not model this: the original
// FileLock keeps reporting `isValid() == true` while the file is, to every other process, free.
// Measured on this JDK, not inferred: a second channel opened and closed, and separately a bare
// read, each let an outside process take the lock while the holder saw nothing.
//
// So a second acquire is refused from [heldPaths] WITHOUT touching the filesystem. The old code
// opened a channel, caught OverlappingFileLockException, and closed it — which released the real
// lock every time, and the test named `daemon lock is single-flight` was the one caller that
// reached it. Production has a single call site, so nothing was ever unlocked in the field; the
// guard is here because the invariant is invisible and the next reader of this file cannot see it.
package splice.app.daemon

import splice.core.util.Cancellables
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

// The lock paths THIS process holds. Not a cache — the guard itself: see the header for why a
// second descriptor for a held path must never be opened. File-scope private val, the sanctioned
// home for a process-wide value (a companion is banned, and DaemonLock has a required ctor).
private val heldPaths: MutableSet<Path> = ConcurrentHashMap.newKeySet()

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var held: Path? = null

    /** Try to acquire the exclusive daemon lock. Returns true if this process is the winner.
     *  A second attempt from THIS process loses without opening anything (DR-162 — opening and
     *  closing a descriptor would drop the lock we already hold); another PROCESS holding it makes
     *  tryLock return null, and there closing our own fresh descriptor is harmless. */
    public fun tryAcquire(): Boolean {
        val path = lockFile.toAbsolutePath().normalize()
        if (!heldPaths.add(path)) return false
        var won = false
        try {
            Files.createDirectories(path.parent)
            val ch = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val fl = try {
                ch.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (fl == null) {
                ch.close()
            } else {
                channel = ch
                lock = fl
                held = path
                won = true
            }
        } finally {
            // Every non-winning exit — a peer process holds it, or the open itself threw — hands
            // the reservation back, or a retry in this process could never win again.
            if (!won) heldPaths.remove(path)
        }
        return won
    }

    override fun close() {
        Cancellables.discard(
            Cancellables.runCatchingCancellable { lock?.release() },
            "process-exit cleanup; the OS reclaims the lock regardless",
        )
        Cancellables.discard(
            Cancellables.runCatchingCancellable { channel?.close() },
            "process-exit cleanup; the OS reclaims the fd regardless",
        )
        // After the descriptor is gone the path is genuinely free again, so the reservation must go
        // with it — a stale entry would refuse every later acquire in this process.
        held?.let { heldPaths.remove(it) }
        held = null
    }
}
