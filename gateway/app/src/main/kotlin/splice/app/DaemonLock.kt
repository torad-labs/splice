// PORT-OF: the launcher's EADDRINUSE quiet-exit intent @ 4ca99f7, adapted for the single
// daemon (P4-SUP slot): one process binds control_port AND every head port, so the per-port
// trick doesn't compose. A flock on ~/.claude-codex/state/daemon.lock is the single-flight
// startup gate — the loser waits briefly, health-checks the winner, exits 0 LOUD (never a loop).
package splice.app

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** Try to acquire the exclusive daemon lock. Returns true if this process is the winner.
     *  Note: FileLock is JVM-wide — a second lock attempt within the SAME process throws
     *  OverlappingFileLockException (separate processes get null); both mean "held by another". */
    public fun tryAcquire(): Boolean {
        Files.createDirectories(lockFile.parent)
        val ch = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val fl = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        return if (fl == null) {
            ch.close()
            false
        } else {
            channel = ch
            lock = fl
            true
        }
    }

    override fun close() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }
}
