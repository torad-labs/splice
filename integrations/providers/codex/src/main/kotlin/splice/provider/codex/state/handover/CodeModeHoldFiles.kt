// NEW: #525 (2026-11-12): the file a retiring daemon holds beside each code-mode conversation it still answers.
package splice.provider.codex.state.handover

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** #525: a code-mode conversation belongs to one daemon at a time. A daemon that retires with a turn of a
 *  conversation still running keeps an OS lock on `<name>.held` beside that conversation's journal until it has
 *  written the conversation for the last time; the next daemon leaves the journal alone while the lock is held. The
 *  OS drops the lock when the process ends, so a daemon that dies leaves only a stale file, which the next one
 *  deletes. The name has no `.jsonl`, so the directory's journal and lock patterns never match it. */
internal object CodeModeHoldFiles {
    const val HELD_SUFFIX: String = ".held"

    /** The conversation name a hold, journal or lock file carries: the hash before the first dot. */
    fun name(file: Path): String = file.fileName.toString().substringBefore('.')

    /** The hold file beside [journal]. */
    fun beside(journal: Path): Path = journal.resolveSibling(name(journal) + HELD_SUFFIX)

    /** An exclusive lock on [hold], created if missing; null when it cannot be taken. */
    fun take(hold: Path): FileLock? {
        val channel = FileChannel.open(hold, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (failure: IOException) {
            channel.close()
            throw failure
        }
        if (lock == null) channel.close()
        return lock
    }

    /** Lets [lock] go and removes its file. */
    fun release(lock: FileLock, hold: Path) {
        try {
            lock.channel().close()
        } finally {
            Files.deleteIfExists(hold)
        }
    }

    /** True while another daemon, or another holder in this one, keeps the lock on [hold]. A missing file is free.
     *  The probe takes a shared lock on a read-only channel, which an exclusive holder refuses. */
    fun heldElsewhere(hold: Path): Boolean {
        if (Files.notExists(hold)) return false
        return try {
            FileChannel.open(hold, StandardOpenOption.READ).use { channel ->
                val probe = channel.tryLock(0L, Long.MAX_VALUE, true)
                probe?.release()
                probe == null
            }
        } catch (_: OverlappingFileLockException) {
            true
        } catch (_: java.nio.file.NoSuchFileException) {
            false
        }
    }
}
