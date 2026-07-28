// NEW: bounded process-wide filesystem lane for turn-path telemetry and state persistence.
package splice.core.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "splice-file-io").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    public fun submit(delayMs: Long = 0L, task: () -> Unit): Boolean {
        if (pending.incrementAndGet() > MAX_PENDING_TASKS) {
            pending.decrementAndGet()
            recordDrop()
            return false
        }
        val guarded = Runnable {
            try {
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

    /** Total tasks dropped since process start (pending cap exceeded or executor rejection). */
    public fun droppedCount(): Int = dropped.get()

    // Observable drop (CONF-1): 3 of 4 callers ignore submit()'s boolean, so a drop was previously
    // silent data loss. Warn once on the first drop rather than spamming stderr under load.
    private fun recordDrop() {
        dropped.incrementAndGet()
        if (warned.compareAndSet(false, true)) {
            System.err.println("[async-file-io] task dropped (pending cap or rejection) — further drops silent")
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
