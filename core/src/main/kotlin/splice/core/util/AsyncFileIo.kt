// NEW: bounded process-wide filesystem lane for turn-path telemetry and state persistence.
package splice.core.util

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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

/** One saturation episode: [dropped] tasks refused since the last report, [sinceStart] since the process began. */
public data class DropEpisode(public val dropped: Int, public val sinceStart: Int) {
    /** The sentence daemon.log carries for this episode. */
    public val line: String
        get() = "[async-file-io] $dropped task(s) dropped while the lane was saturated (pending cap or " +
            "rejection); $sinceStart since start\n"
}

/**
 * Where [AsyncFileIo] writes a drop episode. Runs ON the lane's worker thread, which owns the file, and writes the
 * line straight to it: a sink that submitted the line back would meet the same full lane that dropped the work.
 * Returns true once the line reached the file, and false (never throws) when it did not, so the episode is kept
 * and offered again before the worker's next task.
 */
public fun interface DropSink {
    public operator fun invoke(episode: DropEpisode): Boolean
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
    private val episodeDrops = AtomicInteger()

    @Volatile
    private var dropSink: DropSink? = null
    private val pathLock = Any()
    private val latestByPath = HashMap<Path, CompletableFuture<Boolean>>()
    private val pendingByPath = HashMap<Path, Int>()
    private val failedPaths = HashSet<Path>()

    // Threads come from the platform factory, never from an ad-hoc `Thread(...)`: the factory owns
    // thread group and priority, and this lane only overrides the two properties that are its own
    // contract — the name (so a stack dump says which lane is blocked) and daemon-ness (so a
    // pending file write can never keep a dying JVM alive).
    private val threads = Executors.defaultThreadFactory()
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
                reportDrops()
                task()
            } finally {
                pending.decrementAndGet()
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
                synchronized(pathLock) {
                    if (!written) failedPaths.add(key)
                    val remaining = checkNotNull(pendingByPath[key]) - 1
                    if (remaining == 0) pendingByPath.remove(key) else pendingByPath[key] = remaining
                }
                settled.complete(written)
            }
        }
        if (accepted) {
            pendingByPath[key] = (pendingByPath[key] ?: 0) + 1
        } else {
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

    /** Delayed and runnable slots, with the not-done tracked paths below one temporary root. */
    public data class PendingWrites(public val count: Int, public val paths: List<Path>)

    /** A read-only cleanup diagnostic; untracked tasks still appear in [PendingWrites.count]. */
    public fun pendingUnder(root: Path): PendingWrites = synchronized(pathLock) {
        val normalized = root.toAbsolutePath().normalize()
        PendingWrites(
            pending.get(),
            pendingByPath.keys.filter { path -> path.startsWith(normalized) },
        )
    }

    /** Total tasks dropped since process start (pending cap exceeded or executor rejection). */
    public fun droppedCount(): Int = dropped.get()

    /** Tasks accepted and not yet finished, delayed ones included: a delayed task holds its slot from
     *  submit until it runs. The lane is process-wide, so a test reads the slots others hold here
     *  instead of assuming none (AsyncFileIoTest). */
    internal fun pendingCount(): Int = pending.get()

    /** Where drop episodes are written; null (the start) keeps them counted until a sink is set. */
    public fun reportDropsTo(sink: DropSink?) {
        dropSink = sink
    }

    // The lane cannot report its own saturation through itself: daemon.log is one of its writes, and a line
    // submitted into a full lane is refused like the work it reports. So the worker thread, which owns the file,
    // writes the episode itself before it starts its next task, and the drain marker is such a task, so a
    // shutdown flush cannot finish ahead of a pending report. The count falls only by what was written: a
    // refused write keeps it, and a drop that lands meanwhile is still counted.
    private fun recordDrop() {
        dropped.incrementAndGet()
        episodeDrops.incrementAndGet()
    }

    private fun reportDrops() {
        val sink = dropSink ?: return
        val episode = episodeDrops.get()
        if (episode > 0 && sink(DropEpisode(episode, dropped.get()))) episodeDrops.addAndGet(-episode)
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
