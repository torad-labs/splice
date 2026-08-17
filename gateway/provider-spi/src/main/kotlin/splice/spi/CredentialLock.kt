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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Serializes a credential-file refresh across processes (FileLock) and threads (per-path Mutex). */
public object CredentialLock {

    // SH-06: bounded wait. The old blocking whole-file lock call was justified by POSIX auto-release on
    // a DEAD peer — true, but the real case is a LIVE slow peer: the refresh HTTP hop runs inside
    // the lock (3 attempts x 30s timeouts + backoff ≈ 96s hold), parking every blocking-tier
    // credentials() call here. 15s is comfortably above a healthy refresh RTT, well below the
    // pathological hold. On expiry the refresh runs UNLOCKED (kimi-cli's judgment: bounded beats
    // hung) — G1's other three layers (re-read inside the lock, peer-rotation adoption by token
    // identity, one-shot reread-on-rejection) are precisely the defence for the residual race.
    public const val CREDENTIAL_LOCK_WAIT_MS: Long = 15_000L
    private const val POLL_BASE_MS = 50L
    private const val POLL_MAX_MS = 1_000L
    private const val JITTER_LO = 0.9
    private const val JITTER_HI = 1.1
    private const val CONTENTION_LOG_MS = 1_000L

    // One Mutex per credential path — the intra-JVM half (a FileLock alone would throw
    // OverlappingFileLockException on a same-JVM overlap instead of queueing). Bounded: one entry per
    // distinct authPath (≤3 per process), so no unbounded growth / no eviction needed.
    private val inProcess = ConcurrentHashMap<Path, Mutex>()

    /** Runs [block] while holding an exclusive cross-process lock on `<path>.lock` — or, after
     *  [waitMs] of a peer refusing to yield, WITHOUT it, honestly logged (SH-06). [log] surfaces
     *  contention (waits over 1s) and the unlocked degrade; production wires the head log. */
    public suspend fun <T> withLock(
        path: Path,
        waitMs: Long = CREDENTIAL_LOCK_WAIT_MS,
        log: (String) -> Unit = {},
        // HD-19: the two runtime reaches this primitive used to make directly, as one cohesive
        // argument — `runtime.waiter` paces the tryLock poll (was `delay`) and `runtime.dispatcher`
        // is where the poll runs (was Dispatchers.IO). The default is the production runtime, so a
        // caller that passes nothing is unchanged — and CredentialLockTest can now assert the
        // backoff CURVE instead of waiting out 600ms of it.
        runtime: PollRuntime = PollRuntime(),
        block: suspend () -> T,
    ): T =
        inProcess.computeIfAbsent(path) { Mutex() }.withLock {
            withFileLock(path, waitMs, log, runtime, block)
        }

    private suspend fun <T> withFileLock(
        path: Path,
        waitMs: Long,
        log: (String) -> Unit,
        runtime: PollRuntime,
        block: suspend () -> T,
    ): T {
        // Lock a SIBLING `<name>.lock` file, NEVER the credential file itself — an advisory lock on
        // the auth JSON would make a plain read of it block, which must never happen.
        val lockPath = path.resolveSibling("${path.fileName}.lock")
        val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            // tryLock is non-blocking; the poll loop delays on the IO context so no shared
            // coroutine-dispatcher thread ever parks (the old blocking lock() parked a real one).
            val lock = withContext(runtime.dispatcher) {
                acquireBounded(channel, lockPath, waitMs, log, runtime.waiter)
            }
            try {
                return block()
            } finally {
                lock?.release()
            }
        } finally {
            channel.close()
        }
    }

    /** Jittered tryLock poll up to [waitMs]; null = budget spent, caller proceeds UNLOCKED.
     *  OverlappingFileLockException reads as "held by this JVM through another channel" (the
     *  official CLIs normally contend as separate processes; a same-JVM holder shows up in tests
     *  and in any future shared-path topology) — held is held, keep polling. */
    private suspend fun acquireBounded(
        channel: FileChannel,
        lockPath: Path,
        waitMs: Long,
        log: (String) -> Unit,
        waiter: Waiter,
    ): FileLock? {
        val t0 = System.nanoTime()
        var backoffMs = POLL_BASE_MS
        while (true) {
            val waited = (System.nanoTime() - t0) / NANOS_PER_MS
            val lock = try {
                channel.tryLock()
            } catch (ignored: OverlappingFileLockException) {
                null // held by this JVM via another channel — same contention, same poll
            }
            if (lock != null) {
                if (waited >= CONTENTION_LOG_MS) log("[credential-lock] waited ${waited}ms for a peer on $lockPath")
                return lock
            }
            if (waited >= waitMs) {
                log(
                    "[credential-lock] waited ${waited}ms for a peer on $lockPath; proceeding unlocked — " +
                        "bounded-and-unlocked beats hung; G1's re-read/adopt/reread-on-rejection layers cover the race",
                )
                return null
            }
            waiter.wait((backoffMs * Random.nextDouble(JITTER_LO, JITTER_HI)).toLong())
            backoffMs = minOf(backoffMs * 2, POLL_MAX_MS)
        }
    }

    private const val NANOS_PER_MS = 1_000_000L
}
