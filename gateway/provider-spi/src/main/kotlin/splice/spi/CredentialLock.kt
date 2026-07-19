// NEW: cross-process credential-file serialization (G1, 2026-07-18). Two gateway processes (two
// daemon instances, or ours + the official grok/codex CLI) can both read the same not-yet-rotated
// refresh_token and both POST it — the second POST burns a token the first already rotated, killing
// the credential (the kimi-code token, 2026-07-18). This wraps the read→POST→write of a refresh in a
// java.nio advisory FileLock on a SIBLING `<authPath>.lock` file so only one holder runs at a time.
//
// TWO layers, because a java.nio FileLock is held by the whole JVM, not per-thread: two overlapping
// FileChannel.lock() calls in ONE JVM throw OverlappingFileLockException instead of queueing
// (verified). In production SingleFlight already serializes doRefresh() per provider instance so this
// never happens — but the in-process Mutex below makes same-JVM callers QUEUE rather than throw if a
// topology ever shares a path, and lets the primitive be exercised directly in tests. The FileLock is
// the cross-PROCESS half; the Mutex is the intra-process half.
package splice.spi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** Serializes a credential-file refresh across processes (FileLock) and threads (per-path Mutex). */
public object CredentialLock {

    // One Mutex per credential path — the intra-JVM half (a FileLock alone would throw
    // OverlappingFileLockException on a same-JVM overlap instead of queueing). Bounded: one entry per
    // distinct authPath (≤3 per process), so no unbounded growth / no eviction needed.
    private val inProcess = ConcurrentHashMap<Path, Mutex>()

    /** Runs [block] while holding an exclusive cross-process lock on `<path>.lock`, released after. */
    public suspend fun <T> withLock(path: Path, block: suspend () -> T): T =
        inProcess.computeIfAbsent(path) { Mutex() }.withLock {
            withFileLock(path, block)
        }

    private suspend fun <T> withFileLock(path: Path, block: suspend () -> T): T {
        // Lock a SIBLING `<name>.lock` file, NEVER the credential file itself — an advisory lock on
        // the auth JSON would make a plain read of it block, which must never happen.
        val lockPath = path.resolveSibling("${path.fileName}.lock")
        val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            // channel.lock() BLOCKS a real thread until a peer PROCESS releases (POSIX auto-releases
            // the lock if the holding process dies, so a crashed peer can't wedge it — no timeout knob
            // needed). Push it to the IO pool so it never parks a shared coroutine-dispatcher thread.
            // Fixed process-wide primitive, not a caller-injectable seam.
            // ast-grep-ignore: main-no-hardcoded-dispatchers -- fixed IO pool for a blocking OS file lock
            val lock = withContext(Dispatchers.IO) { channel.lock() }
            try {
                return block()
            } finally {
                lock.release()
            }
        } finally {
            channel.close()
        }
    }
}
