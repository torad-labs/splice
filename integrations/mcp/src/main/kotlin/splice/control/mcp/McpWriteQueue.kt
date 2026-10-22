package splice.control.mcp

import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class McpWrite(
    val target: McpWriteTarget,
    val bytes: Long,
    val requestId: Long?,
    val serverRequestId: Long?,
) {
    var encoded: String? = null
    val completed = CompletableDeferred<Boolean>()
    var deadline: ScheduledFuture<*>? = null
}

internal fun interface McpWritePermitted {
    operator fun invoke(delivery: McpWrite): Boolean
}

internal fun interface McpWriteResolved {
    operator fun invoke(delivery: McpWrite, delivered: Boolean): Boolean
}

internal fun interface McpWriteRejected {
    operator fun invoke(delivery: McpWrite)
}

/** Owns the bounded writer executor and drops no task silently when its queue reaches capacity. */
internal class McpWriteQueue(
    name: String,
    private val permitted: McpWritePermitted,
    private val resolved: McpWriteResolved,
    private val failures: McpWriteFailures,
    private val rejected: McpWriteRejected,
) {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(WRITE_QUEUE_CAPACITY),
        { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                this.name = "mcp-host-write-$name"
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun offer(delivery: McpWrite): Boolean = try {
        executor.execute { write(delivery) }
        true
    } catch (_: RejectedExecutionException) {
        rejected(delivery)
        false
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun write(delivery: McpWrite) {
        try {
            resolved(delivery, writeToPipe(delivery))
        } catch (failure: IOException) {
            if (resolved(delivery, false)) {
                failures.report(delivery.target.child, "write failed: ${failure.message}")
            }
        }
    }

    private fun writeToPipe(delivery: McpWrite): Boolean = synchronized(delivery.target.ioLock) {
        if (!permitted(delivery)) return@synchronized false
        val encoded = delivery.encoded ?: return@synchronized false
        delivery.target.output.write(encoded)
        delivery.target.output.newLine()
        delivery.target.output.flush()
        true
    }
}
