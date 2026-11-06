package splice.upstream.observability

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.AsyncFileIo
import splice.upstream.memory.JvmHeap

/** App composes existing owners through the core port; observability never depends on upstream. */
public object JvmHeapRuntimeSource : PerfRuntimeSource {
    override fun runtime(): JsonObject = buildJsonObject {
        val memory = JvmHeap.memorySnapshot()
        val work = AsyncFileIo.workSnapshot
        put(
            "measurements",
            buildJsonObject {
                put("runtime_heap_used_bytes", memory.heap.usedBytes)
                put("runtime_heap_committed_bytes", memory.heap.committedBytes)
                put("runtime_heap_max_bytes", memory.heap.maxBytes?.let(::JsonPrimitive) ?: JsonNull)
                put("runtime_nonheap_used_bytes", memory.nonHeap.usedBytes)
                put("runtime_nonheap_committed_bytes", memory.nonHeap.committedBytes)
                put("runtime_direct_bytes", memory.direct?.usedBytes?.let(::JsonPrimitive) ?: JsonNull)
                put("runtime_mapped_bytes", memory.mapped?.usedBytes?.let(::JsonPrimitive) ?: JsonNull)
                put("runtime_rss_bytes", memory.residentBytes?.let(::JsonPrimitive) ?: JsonNull)
                put("runtime_pss_bytes", JvmProportionalMemory.bytes()?.let(::JsonPrimitive) ?: JsonNull)
                put("runtime_executor_queued", work.queued)
                put("runtime_executor_active", work.running)
            },
        )
    }
}
