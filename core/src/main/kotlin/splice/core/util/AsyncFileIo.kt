// NEW: bounded process-wide filesystem lane for turn-path telemetry and state persistence.
package splice.core.util

import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One best-effort write handed to the [AsyncFileIo] lane — a perf/compact JSONL append, a usage
 * flush, a state-file rewrite.
 *
 * Named rather than left a bare `() -> Unit` (HD-22) because the seam carries a real contract the
 * shape does not: it runs on the single `splice-file-io` DAEMON thread, off the turn coroutine, so
 * it must not assume a coroutine context, must not block for long (it holds the one lane every
 * other writer queues behind), and MAY NEVER RUN AT ALL — [submit] returns false at the pending
 * cap and on executor rejection, and a daemon thread is not drained at JVM exit. Anything that must
 * happen is not one of these.
 *
 * The same role as `UsageHud.scheduleCoalesced`'s flush parameter, which exists only to be
 * submitted here, so both name this one type.
 */
public fun interface FileIoTask {
    public operator fun invoke()
}

/**
 * One bounded, process-wide lane for best-effort state/telemetry writes.
 *
 * Turn coroutines enqueue immutable payloads and continue; the daemon thread owns filesystem
 * latency. The explicit pending cap prevents observability from becoming a second unbounded queue.
 */
public object AsyncFileIo {
    private val pending = AtomicInteger()
    private val dropped = AtomicInteger()
    private val warned = AtomicBoolean(false)
    private val pathLock = Any()
    private val latestByPath = HashMap<Path, CompletableFuture<Boolean>>()
    private val failedPaths = HashSet<Path>()

    // Threads come from the platform factory, never from an ad-hoc `Thread(...)`: the factory owns
    // thread group and priority, and this lane only overrides the two properties that are its own
    // contract — the name (so a stack dump says which lane is blocked) and daemon-ness (so a
    // pending file write can never keep a dying JVM alive).
    private val threads = Executors.defaultThreadFactory()
    private val warnings = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { task ->
            threads.newThread(task).apply {
                name = "splice-file-io-warning"
                isDaemon = true
            }
        },
        ThreadPoolExecutor.DiscardPolicy(),
    )
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        threads.newThread(task).apply {
            name = "splice-file-io"
            isDaemon = true
        }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    public fun submit(delayMs: Long = 0L, task: FileIoTask): Boolean {
        if (pending.incrementAndGet() > MAX_PENDING_TASKS) {
            pending.decrementAndGet()
            recordDrop()
            return false
        }
        val guarded = Runnable {
            try {
                task()
            } finally {
                if (pending.decrementAndGet() == 0) warned.set(false)
            }
        }
        return try {
            if (delayMs > 0) {
                executor.schedule(guarded, delayMs, TimeUnit.MILLISECONDS)
            } else {
                executor.execute(guarded)
            }
            true
        } catch (_: RejectedExecutionException) {
            pending.decrementAndGet()
            recordDrop()
            false
        }
    }

    /** An accepted file task and its read barrier share one path and one lane position. A refusal
     * remains visible until a later accepted task for that path replaces it. */
    public fun submitFor(file: Path, task: FileIoTask): Boolean = synchronized(pathLock) {
        val key = file.toAbsolutePath().normalize()
        val settled = CompletableFuture<Boolean>()
        val accepted = submit {
            var written = false
            try {
                task()
                written = true
            } finally {
                if (!written) synchronized(pathLock) { failedPaths.add(key) }
                settled.complete(written)
            }
        }
        if (!accepted) {
            failedPaths.add(key)
            settled.complete(false)
        }
        latestByPath[key] = settled
        accepted
    }

    /** Wait only for writes submitted before this read for [file], not all other heads' writes.
     * False means an enqueue was dropped or the bounded wait expired; no missing row is claimed. */
    public fun awaitFile(file: Path, timeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS): Boolean {
        val key = file.toAbsolutePath().normalize()
        val captured = synchronized(pathLock) { latestByPath[key] }
        return awaitSettled(captured, timeoutMs) && synchronized(pathLock) { key !in failedPaths }
    }

    /** A directory inventory needs all already-submitted live-file writes before it lists files. */
    public fun awaitDirectory(dir: Path, timeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS): Boolean {
        val root = dir.toAbsolutePath().normalize()
        val captured = synchronized(pathLock) { latestByPath.filterKeys { it.parent == root }.toMap() }
        return captured.all { (key, write) ->
            awaitSettled(write, timeoutMs) && synchronized(pathLock) { key !in failedPaths }
        }
    }

    private fun awaitSettled(write: CompletableFuture<Boolean>?, timeoutMs: Long): Boolean = try {
        write?.get(timeoutMs, TimeUnit.MILLISECONDS) ?: true
    } catch (_: TimeoutException) {
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** Total tasks dropped since process start (pending cap exceeded or executor rejection). */
    public fun droppedCount(): Int = dropped.get()

    /** Tasks accepted and not yet finished, delayed ones included: a delayed task holds its slot from
     *  submit until it runs. The lane is process-wide, so a test reads the slots others hold here
     *  instead of assuming none (AsyncFileIoTest). */
    internal fun pendingCount(): Int = pending.get()

    // The file lane cannot report its own saturation through daemon.log (which it writes).
    // A separate bounded warning lane owns stderr, so even a blocked boot log cannot stall a turn.
    // One warning per episode; draining the file lane re-arms the next transition.
    private fun recordDrop() {
        val total = dropped.incrementAndGet()
        if (warned.compareAndSet(false, true)) {
            warnings.execute {
                // ast-grep-ignore: kt-no-println -- the file lane cannot log its own rejection through itself
                System.err.println("[async-file-io] $total task(s) dropped (pending cap or rejection)")
            }
        }
    }

    /** Wait for all currently runnable work; delayed tasks remain delayed. Intended for reads/tests/shutdown. */
    public fun drain(timeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS): Boolean {
        val latch = CountDownLatch(1)
        if (!submit { latch.countDown() }) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private const val MAX_PENDING_TASKS = 2_048
    private const val DEFAULT_DRAIN_TIMEOUT_MS = 5_000L
}
