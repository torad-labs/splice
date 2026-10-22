package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.IOException

internal data class McpWriteTarget(val child: Process, val output: BufferedWriter)

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
    private val lock = Any()
    private val requests = HashMap<Long, Process>()
    private val serverRequests = HashMap<Long, Process>()

    val unsettled: List<Long>
        get() = synchronized(lock) {
            requests.entries.removeIf { !it.value.isAlive }
            serverRequests.entries.removeIf { !it.value.isAlive }
            (requests.keys + serverRequests.keys).toList()
        }

    /** Holds a server-initiated request from receipt until its reply fully writes or this exact child exits. */
    fun retain(requestId: Long, child: Process): Boolean = synchronized(lock) {
        if (!child.isAlive) {
            false
        } else {
            serverRequests[requestId] = child
            true
        }
    }

    fun idsOf(child: Process): List<Long> = synchronized(lock) {
        requests.entries.filter { it.value === child }.map { it.key }
    }

    fun settled(id: Long) {
        synchronized(lock) { requests.remove(id) }
    }

    fun exited(child: Process) {
        synchronized(lock) {
            requests.entries.removeIf { it.value === child }
            serverRequests.entries.removeIf { it.value === child }
        }
    }

    /** Writes a reply only to [child]'s generation, releasing its retained charge after the flush. */
    fun reply(msg: JsonObject, requestId: Long, child: Process): Boolean {
        val written = invoke(msg, expected = child)
        if (written) synchronized(lock) { serverRequests.remove(requestId) }
        return written
    }

    operator fun invoke(msg: JsonObject, requestId: Long? = null, expected: Process? = null): Boolean {
        var generation: Process? = null
        return try {
            synchronized(lock) {
                val selected = target(expected) ?: return false
                generation = selected.child
                requestId?.let { requests[it] = selected.child }
                selected.output.write(codec.encode(msg))
                selected.output.newLine()
                selected.output.flush()
            }
            true
        } catch (failure: IOException) {
            generation?.let { failed(it, "write failed: ${failure.message}") }
            false
        }
    }
}
