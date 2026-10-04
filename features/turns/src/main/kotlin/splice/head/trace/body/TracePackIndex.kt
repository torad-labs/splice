// NEW: one active day's chunk and weak-literal indexes own their charged capacity.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.upstream.memory.JvmHeap
import java.util.UUID
import java.util.WeakHashMap

// why: a hash key, JSON reference fields, map nodes and table growth coexist for each index entry.
private const val TRACE_INDEX_ENTRY_BYTES = 1024L

/** Readers never share this writer-local index or retain a global body cache. */
internal class TracePackIndex(val heap: HeapBudget = JvmHeap.budget) {
    var generation: UUID? = null
    var end: Long = TRACE_PACK_START_BYTES.toLong()
    var tail: JsonObject? = null
    val chunks = HashMap<String, JsonObject>()

    // Weak keys reuse queued equal literals without keeping completed request bodies alive.
    val literals = WeakHashMap<String, JsonArray>()
    private val chunkLease = HeapOwners.charge(chunks, heap, 0L)
    private val literalLease = HeapOwners.charge(literals, heap, 0L)

    fun admitChunk() {
        val needed = (chunks.size + 1L) * TRACE_INDEX_ENTRY_BYTES
        if (!chunkLease.resize(maxOf(chunkLease.bytes, needed))) throw HeapCapacityException()
    }

    fun cache(text: String, parts: JsonArray) {
        val needed = (literals.size + 1L) * TRACE_INDEX_ENTRY_BYTES
        if (!literalLease.resize(maxOf(literalLease.bytes, needed))) throw HeapCapacityException()
        HeapOwners.charge(parts, heap, HeapJson.bytes(parts))
        literals[text] = parts
    }
}
