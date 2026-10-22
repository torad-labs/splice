package splice.control.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Bounds queued writes and unsettled exchanges per direction, so caller timeouts cannot grow retained wire state. */
private const val WRITE_QUEUE_CAPACITY = 64

/** One child/stdin generation, carrying its own short-lived retirement fence. */
internal data class McpWriteTarget(
    val child: Process,
    val output: BufferedWriter,
) {
    val ioLock = Any()
    var sealed = false
    var retired = false
}

internal fun interface McpWriteTargetSource {
    operator fun invoke(expected: Process?): McpWriteTarget?
}

internal fun interface McpWriteFailure {
    operator fun invoke(child: Process, reason: String)
}

private data class McpWrite(
    val target: McpWriteTarget,
    val encoded: String,
    val requestId: Long?,
    val serverRequestId: Long?,
) {
    val completed = CompletableDeferred<Boolean>()
    var deadline: ScheduledFuture<*>? = null
}

internal fun interface McpWriteExpired {
    operator fun invoke()
}

internal fun interface McpFenceHeld {
    operator fun invoke(target: McpWriteTarget): Boolean
}

internal fun interface McpSettled {
    operator fun invoke(id: Long)
}

private fun interface McpWritePermitted {
    operator fun invoke(delivery: McpWrite): Boolean
}

private fun interface McpWriteResolved {
    operator fun invoke(delivery: McpWrite, delivered: Boolean): Boolean
}

private fun interface McpWriteRejected {
    operator fun invoke(delivery: McpWrite)
}

/** Owns deadline and failure work, so pipe callers never execute terminal teardown inline. */
internal class McpWriteFailures(name: String, private val failed: McpWriteFailure) {
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
        try {
            executor.execute { failed(child, reason) }
        } catch (_: RejectedExecutionException) {
            // A closed owner already retires the child.
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}

/** Owns the bounded writer executor and drops no task silently when its queue reaches capacity. */
private class McpWriteQueue(
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
        delivery.target.output.write(delivery.encoded)
        delivery.target.output.newLine()
        delivery.target.output.flush()
        true
    }
}

/** The exact generation a replacement sealed, which can reopen only while it is still live and sealed. */
internal class McpReplacementFence(
    private val writer: McpChildWriter,
    private val target: McpWriteTarget,
) {
    fun held(): Boolean = writer.fenceHeld(target)

    fun reopen(): Boolean = writer.reopen(target)
}

/** Bounded writes and their accounting share one generation fence, so retirement cannot strand a pending call. */
internal class McpChildWriter(
    name: String,
    private val codec: JsonRpcCodec,
    private val target: McpWriteTargetSource,
    private val failed: McpWriteFailure,
) {
    /** Protects generation state and wire accounting only. It never covers a pipe write. */
    private val accountingLock = Any()
    private val requests = HashMap<Long, McpWriteTarget>()
    private val serverRequests = HashMap<Long, McpWriteTarget>()
    private val writesInFlight = Collections.newSetFromMap(IdentityHashMap<McpWrite, Boolean>())
    internal val failures = McpWriteFailures(name, failed)
    private val permitted = McpWritePermitted { delivery ->
        synchronized(accountingLock) { !delivery.completed.isCompleted && accepting(delivery.target) }
    }
    private val writes = McpWriteQueue(
        name,
        permitted,
        McpWriteResolved(::resolve),
        failures,
        McpWriteRejected { delivery -> expire(delivery, "write queue is full") },
    )
    private var closed = false

    val unsettled: List<Long>
        get() = synchronized(accountingLock) { (requests.keys + serverRequests.keys).toList() }

    /** Atomically stops new work on a quiet generation before replacement commits. */
    fun seal(selected: McpWriteTarget): Boolean = synchronized(accountingLock) {
        if (closed || !selected.child.isAlive) return@synchronized false
        if (selected.retired || selected.sealed) return@synchronized false
        val unsettled = requests.values.any { it === selected } || serverRequests.values.any { it === selected }
        if (unsettled || writesInFlight.any { it.target === selected }) {
            false
        } else {
            selected.sealed = true
            true
        }
    }

    /** A failed replacement may reopen only the same live generation that its fence still owns. */
    fun reopen(selected: McpWriteTarget): Boolean = synchronized(accountingLock) {
        val reopen = selected.sealed && !selected.retired && selected.child.isAlive
        if (reopen) selected.sealed = false
        reopen
    }

    internal val fenceHeld = McpFenceHeld { selected ->
        synchronized(accountingLock) { selected.sealed && !selected.retired }
    }

    /** Terminal retirement rejects late registrations and completes waiters, but preserves unknown wire charges. */
    fun retire(selected: McpWriteTarget): Boolean {
        var retiredNow = false
        val cancelled = synchronized(accountingLock) {
            if (selected.retired) {
                emptyList()
            } else {
                retiredNow = true
                selected.retired = true
                selected.sealed = false
                writesInFlight.filter { it.target === selected }
            }
        }
        cancelled.forEach { resolve(it, false) }
        return retiredNow
    }

    /** A confirmed child exit is the only point that clears its unknown wire charges. */
    fun exited(selected: McpWriteTarget) {
        synchronized(accountingLock) {
            requests.entries.removeIf { it.value === selected }
            serverRequests.entries.removeIf { it.value === selected }
        }
    }

    internal val settled = McpSettled { id ->
        synchronized(accountingLock) { requests.remove(id) }
    }

    /** Charges a reverse request before its reply queue can race the replacement fence. */
    fun retain(requestId: Long, child: Process): Boolean {
        val selected = target(child) ?: return false
        return synchronized(accountingLock) {
            val available = accepting(selected) && child.isAlive && serverRequests.size < WRITE_QUEUE_CAPACITY
            if (available) serverRequests[requestId] = selected
            available
        }
    }

    suspend fun request(msg: JsonObject, requestId: Long, expected: Process, budget: Duration): Boolean =
        submit(msg, requestId, null, expected, budget)?.completed?.await() ?: false

    suspend fun notify(msg: JsonObject, expected: Process? = null, budget: Duration): Boolean =
        submit(msg, null, null, expected, budget)?.completed?.await() ?: false

    /** Queues a reverse reply without making the stdout pump wait for a blocked stdin. */
    fun reply(msg: JsonObject, requestId: Long, child: Process, budget: Duration): Boolean =
        submit(msg, null, requestId, child, budget) != null

    fun close() {
        val cancelled = synchronized(accountingLock) {
            closed = true
            writesInFlight.toList()
        }
        cancelled.forEach { resolve(it, false) }
        writes.close()
        failures.close()
    }

    private fun submit(
        msg: JsonObject,
        requestId: Long?,
        serverRequestId: Long?,
        expected: Process?,
        budget: Duration,
    ): McpWrite? {
        val selected = target(expected) ?: return null
        val delivery = McpWrite(selected, codec.encode(msg), requestId, serverRequestId)
        val registered = synchronized(accountingLock) {
            val room = requestId == null || requests.size < WRITE_QUEUE_CAPACITY
            val available = accepting(selected) && room
            if (available) {
                requestId?.let { requests[it] = selected }
                serverRequestId?.let { serverRequests[it] = selected }
                writesInFlight += delivery
            }
            available
        }
        if (!registered) return null
        queue(delivery, budget)
        return delivery
    }

    private fun queue(delivery: McpWrite, budget: Duration) {
        val deadline = failures.schedule(budget, McpWriteExpired { expire(delivery, "write deadline exceeded") })
        if (deadline == null) {
            expire(delivery, "writer closed before delivery")
        } else {
            val active = synchronized(accountingLock) {
                delivery.deadline = deadline
                if (delivery.completed.isCompleted) deadline.cancel(false)
                !delivery.completed.isCompleted
            }
            if (active) writes.offer(delivery)
        }
    }

    private fun accepting(selected: McpWriteTarget): Boolean =
        !closed && !selected.retired && !selected.sealed

    private fun expire(delivery: McpWrite, reason: String) {
        if (resolve(delivery, false)) failures.report(delivery.target.child, reason)
    }

    private fun resolve(write: McpWrite, delivered: Boolean): Boolean {
        if (!write.completed.complete(delivered)) return false
        synchronized(accountingLock) {
            write.deadline?.cancel(false)
            writesInFlight.remove(write)
            if (delivered) write.serverRequestId?.let(serverRequests::remove)
        }
        return true
    }
}
