package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonWire
import java.io.BufferedWriter
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration

// why: 64 slots bound per-direction exchange tracking when a child stops consuming stdin.
internal const val WRITE_QUEUE_CAPACITY = 64

// why: one MiB caps encoded writes retained while a child is not consuming stdin.
private const val MAX_QUEUED_WRITE_BYTES = 1024L * 1024L

// why: reverse replies share the write ceiling so they cannot wait outside its byte bound.
private const val MAX_RETAINED_REPLY_BYTES = MAX_QUEUED_WRITE_BYTES

// why: supported Linux/macOS runtimes add one newline byte to each JSON-RPC line.
private const val WRITE_LINE_BYTES = 1L

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

internal fun interface McpFenceHeld {
    operator fun invoke(target: McpWriteTarget): Boolean
}

internal fun interface McpSettled {
    operator fun invoke(id: Long)
}

internal fun interface McpFenceAction<T> {
    operator fun invoke(): T
}

/** Publication and late reverse-work admission share the same accounting monitor. */
internal class McpFenceExecutor<L : Any>(private val lock: L, private val held: McpFenceHeld) {
    fun <T> whileHeld(target: McpWriteTarget, action: McpFenceAction<T>): T? = synchronized(lock) {
        if (held(target)) action() else null
    }
}

/** The exact generation a replacement sealed, which can reopen only while it is still live and sealed. */
internal class McpReplacementFence(
    private val writer: McpChildWriter,
    private val target: McpWriteTarget,
) {
    fun held(): Boolean = writer.fenceHeld(target)

    fun <T> whileHeld(action: McpFenceAction<T>): T? = writer.publication.whileHeld(target, action)

    fun reopen(): Boolean = writer.reopen(target)
}

private fun interface McpWriteTargetAcceptance {
    operator fun invoke(target: McpWriteTarget): Boolean
}

private data class McpReverseReply(
    val requestId: Long,
    val target: McpWriteTarget,
    val encoded: String,
    val bytes: Long,
    val budget: Duration,
    var retrying: Boolean = false,
)

private fun interface McpReverseReplyHeld {
    operator fun invoke(requestId: Long, target: McpWriteTarget): Boolean
}

private fun interface McpReverseReplyAttempt {
    operator fun invoke(reply: McpReverseReply): Boolean
}

/** Holds one never-queued reverse response per retained request until another write frees stdin capacity. */
private class McpReverseReplies<L : Any>(
    private val lock: L,
    private val held: McpReverseReplyHeld,
    private val attempt: McpReverseReplyAttempt,
) {
    private val replies = LinkedHashMap<Long, McpReverseReply>()
    private var retainedBytes = 0L

    fun retain(reply: McpReverseReply): Boolean = synchronized(lock) {
        val byteRoom = reply.bytes <= MAX_RETAINED_REPLY_BYTES &&
            retainedBytes <= MAX_RETAINED_REPLY_BYTES - reply.bytes
        when {
            !held(reply.requestId, reply.target) -> false
            replies.containsKey(reply.requestId) -> false
            !byteRoom -> false
            else -> {
                replies[reply.requestId] = reply
                retainedBytes += reply.bytes
                true
            }
        }
    }

    fun retry() {
        val reply = synchronized(lock) {
            replies.values.firstOrNull { !it.retrying && held(it.requestId, it.target) }?.also { it.retrying = true }
        } ?: return
        val queued = attempt(reply)
        synchronized(lock) {
            val retained = replies[reply.requestId]
            if (retained === reply) {
                if (queued) {
                    replies.remove(reply.requestId)
                    retainedBytes -= reply.bytes
                } else {
                    retained.retrying = false
                }
            }
        }
    }

    fun exited(target: McpWriteTarget) = synchronized(lock) {
        replies.values.filter { it.target === target }.forEach { reply ->
            replies.remove(reply.requestId)
            retainedBytes -= reply.bytes
        }
    }
}

/** Atomically reserves retained wire bytes and the client request mapping before a write is encoded. */
private class McpWriteAdmission<L : Any>(
    private val lock: L,
    private val requests: MutableMap<Long, McpWriteTarget>,
    private val inFlight: MutableSet<McpWrite>,
    private val accepting: McpWriteTargetAcceptance,
) {
    private var retainedBytes = 0L

    fun reserve(
        target: McpWriteTarget,
        bytes: Long,
        requestId: Long?,
        serverRequestId: Long?,
    ): McpWrite? = synchronized(lock) {
        val requestRoom = requestId == null || requests.size < WRITE_QUEUE_CAPACITY
        val byteRoom = bytes <= MAX_QUEUED_WRITE_BYTES && retainedBytes <= MAX_QUEUED_WRITE_BYTES - bytes
        val room = requestRoom && byteRoom
        if (!accepting(target) || !room) {
            null
        } else {
            McpWrite(target, bytes, requestId, serverRequestId).also { delivery ->
                requestId?.let { requests[it] = target }
                inFlight += delivery
                retainedBytes += bytes
            }
        }
    }

    /** The caller holds [lock], so release stays atomic with terminal request cleanup. */
    fun release(delivery: McpWrite) {
        inFlight.remove(delivery)
        retainedBytes -= delivery.bytes
    }
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
    private var closed = false
    private val accepting = McpWriteTargetAcceptance { selected ->
        !closed && !selected.retired && !selected.sealed
    }
    private val admission = McpWriteAdmission(
        accountingLock,
        requests,
        writesInFlight,
        accepting,
    )
    private val reverseReplies = McpReverseReplies(
        accountingLock,
        McpReverseReplyHeld { requestId, selected ->
            !closed && !selected.retired && selected.child.isAlive && serverRequests[requestId] === selected
        },
        McpReverseReplyAttempt { reply ->
            val delivery = admission.reserve(reply.target, reply.bytes, null, reply.requestId)
            if (delivery == null) {
                false
            } else {
                delivery.encoded = reply.encoded
                queue(delivery, reply.budget)
            }
        },
    )
    private val permitted = McpWritePermitted { delivery ->
        synchronized(accountingLock) { !delivery.completed.isCompleted && accepting(delivery.target) }
    }
    private val writes = McpWriteQueue(
        name,
        permitted,
        McpWriteResolved(::resolve),
        failures,
        McpWriteRejected { delivery -> resolve(delivery, delivered = false, unqueued = true) },
        McpWriteAvailable(reverseReplies::retry),
    )

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
        synchronized(accountingLock) {
            !closed && selected.sealed && !selected.retired && selected.child.isAlive
        }
    }
    internal val publication = McpFenceExecutor(accountingLock, fenceHeld)

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

    /** A confirmed child exit is the only point that clears its unknown wire charges and retained replies. */
    fun exited(selected: McpWriteTarget) {
        synchronized(accountingLock) {
            requests.entries.removeIf { it.value === selected }
            serverRequests.entries.removeIf { it.value === selected }
        }
        reverseReplies.exited(selected)
    }

    internal val settled = McpSettled { id ->
        synchronized(accountingLock) { requests.remove(id) }
    }

    /** Late reverse work invalidates a quiet replacement fence without killing the still-owned incumbent. */
    fun retain(requestId: Long, child: Process): Boolean {
        val selected = target(child) ?: return false
        return synchronized(accountingLock) {
            val live = !closed && !selected.retired && child.isAlive
            val available = live && serverRequests.size < WRITE_QUEUE_CAPACITY
            if (available) {
                selected.sealed = false
                serverRequests[requestId] = selected
            }
            available
        }
    }

    suspend fun request(msg: JsonObject, requestId: Long, expected: Process, budget: Duration): Boolean =
        submit(msg, requestId, null, expected, budget)?.completed?.await() ?: false

    suspend fun notify(msg: JsonObject, expected: Process? = null, budget: Duration): Boolean =
        submit(msg, null, null, expected, budget)?.completed?.await() ?: false

    /** A known unqueued reply stays retained for this exact live generation until a later stdin slot accepts it. */
    fun reply(msg: JsonObject, requestId: Long, child: Process, budget: Duration): Boolean {
        if (submit(msg, null, requestId, child, budget) != null) return true
        val selected = target(child) ?: return false
        return reverseReplies.retain(
            McpReverseReply(
                requestId,
                selected,
                codec.encode(msg),
                JsonWire.byteSize(msg) + WRITE_LINE_BYTES,
                budget,
            ),
        )
    }

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
        val bytes = JsonWire.byteSize(msg) + WRITE_LINE_BYTES
        val delivery = admission.reserve(selected, bytes, requestId, serverRequestId) ?: return null
        delivery.encoded = codec.encode(msg)
        return delivery.takeIf { queue(it, budget) }
    }

    private fun queue(delivery: McpWrite, budget: Duration): Boolean {
        if (!writes.offer(delivery)) return false
        val deadline = failures.schedule(
            budget,
            McpWriteExpired {
                if (resolve(delivery, false)) {
                    failures.report(delivery.target.child, "write deadline exceeded")
                }
            },
        )
        if (deadline == null) {
            if (resolve(delivery, false)) {
                failures.report(delivery.target.child, "writer closed before delivery")
            }
        } else {
            synchronized(accountingLock) {
                delivery.deadline = deadline
                if (delivery.completed.isCompleted) deadline.cancel(false)
            }
        }
        return true
    }

    /** An unqueued executor refusal releases only work that never reached the child. */
    private fun resolve(write: McpWrite, delivered: Boolean, unqueued: Boolean = false): Boolean {
        val completed = write.completed.complete(delivered)
        synchronized(accountingLock) {
            if (completed) {
                write.deadline?.cancel(false)
                admission.release(write)
                if (unqueued) write.requestId?.let(requests::remove)
            }
            if (delivered) write.serverRequestId?.let(serverRequests::remove)
        }
        return completed
    }
}
