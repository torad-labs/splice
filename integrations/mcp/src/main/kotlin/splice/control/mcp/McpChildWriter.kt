package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.IOException

/** An immutable child/stdin generation. Its lock serializes only this child's pipe writes. */
internal data class McpWriteTarget(
    val child: Process,
    val output: BufferedWriter,
) {
    val ioLock = Any()
}

internal fun interface McpWriteTargetSource {
    operator fun invoke(expected: Process?): McpWriteTarget?
}

internal fun interface McpWriteFailure {
    operator fun invoke(child: Process, reason: String)
}

/** Each write and its wire accounting belong to one immutable child/writer generation. */
internal class McpChildWriter(
    private val codec: JsonRpcCodec,
    private val target: McpWriteTargetSource,
    private val failed: McpWriteFailure,
) {
    /** Protects delivery accounting only. It must never cover a pipe write. */
    private val accountingLock = Any()
    private val requests = HashMap<Long, Process>()
    private val serverRequests = HashMap<Long, Process>()

    val unsettled: List<Long>
        get() = synchronized(accountingLock) {
            requests.entries.removeIf { !it.value.isAlive }
            serverRequests.entries.removeIf { !it.value.isAlive }
            (requests.keys + serverRequests.keys).toList()
        }

    /** Holds a server-initiated request from receipt until its reply fully writes or this exact child exits. */
    fun retain(requestId: Long, child: Process): Boolean = synchronized(accountingLock) {
        if (!child.isAlive) {
            false
        } else {
            serverRequests[requestId] = child
            true
        }
    }

    fun idsOf(child: Process): List<Long> = synchronized(accountingLock) {
        requests.entries.filter { it.value === child }.map { it.key }
    }

    fun settled(id: Long) {
        synchronized(accountingLock) { requests.remove(id) }
    }

    fun exited(child: Process) {
        synchronized(accountingLock) {
            requests.entries.removeIf { it.value === child }
            serverRequests.entries.removeIf { it.value === child }
        }
    }

    /** Writes a reply only to [child]'s generation, releasing its retained charge after the flush. */
    fun reply(msg: JsonObject, requestId: Long, child: Process): Boolean {
        val written = invoke(msg, expected = child)
        if (written) synchronized(accountingLock) { serverRequests.remove(requestId) }
        return written
    }

    operator fun invoke(msg: JsonObject, requestId: Long? = null, expected: Process? = null): Boolean {
        val encoded = codec.encode(msg)
        val selected = target(expected) ?: return false
        requestId?.let {
            synchronized(accountingLock) { requests[it] = selected.child }
        }
        return try {
            synchronized(selected.ioLock) {
                selected.output.write(encoded)
                selected.output.newLine()
                selected.output.flush()
            }
            true
        } catch (failure: IOException) {
            failed(selected.child, "write failed: ${failure.message}")
            false
        }
    }
}
