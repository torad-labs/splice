package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
    private val requests = ConcurrentHashMap<Long, Process>()

    val unsettled: List<Long>
        get() {
            requests.entries.removeIf { !it.value.isAlive }
            return requests.keys.toList()
        }

    fun idsOf(child: Process): List<Long> = requests.entries.filter { it.value === child }.map { it.key }

    fun settled(id: Long) {
        requests.remove(id)
    }

    fun exited(child: Process) {
        requests.entries.removeIf { it.value === child }
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
