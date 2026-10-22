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

/** Owns deadline and failure work, so pipe callers never execute terminal teardown inline. */
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

    fun report(child: Process, reason: String) {
        if (!synchronized(pending) { pending.add(child) }) return
        try {
            executor.execute { dispatch(child, reason) }
        } catch (_: RejectedExecutionException) {
            synchronized(pending) { pending.remove(child) }
        }
    }

    private fun dispatch(child: Process, reason: String) {
        try {
            failed(child, reason)
        } finally {
            synchronized(pending) { pending.remove(child) }
        }
    }

    fun close() {
        executor.shutdownNow()
        synchronized(pending) { pending.clear() }
    }
}
