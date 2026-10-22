package splice.control.mcp

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

internal fun interface McpWriteExpired {
    operator fun invoke()
}

/** Schedules deadlines and drains failure work after its owner synchronously fenced the exact transport. */
internal class McpWriteFailures(name: String, private val failed: McpWriteFailure) {
    private val pending = Collections.newSetFromMap(IdentityHashMap<Process, Boolean>())
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Executors.defaultThreadFactory().newThread(task).apply {
            this.name = "mcp-host-write-deadline-$name"
            isDaemon = true
        }
    }.apply {
        removeOnCancelPolicy = true
    }

    fun schedule(budget: Duration, expired: McpWriteExpired): ScheduledFuture<*>? = try {
        executor.schedule(
            expired::invoke,
            budget.inWholeMilliseconds.coerceAtLeast(1L),
            TimeUnit.MILLISECONDS,
        )
    } catch (_: RejectedExecutionException) {
        null
    }

    /** Fence and publish first. A second accepted failure waits for that publication, then shares its drain. */
    fun publish(child: Process, reason: String): Boolean {
        val drain = synchronized(pending) {
            if (!pending.add(child)) {
                null
            } else {
                failed(child, reason).also { published ->
                    if (published == null) pending.remove(child)
                }
            }
        } ?: return false
        try {
            executor.execute { dispatch(child, drain) }
        } catch (_: RejectedExecutionException) {
            synchronized(pending) { pending.remove(child) }
        }
        return true
    }

    private fun dispatch(child: Process, drain: McpWriteFailureDrain) {
        try {
            drain()
        } finally {
            synchronized(pending) { pending.remove(child) }
        }
    }

    fun close() {
        executor.shutdownNow()
        synchronized(pending) { pending.clear() }
    }
}
