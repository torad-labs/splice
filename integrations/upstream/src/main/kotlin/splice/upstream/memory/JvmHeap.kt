// NEW: the JVM runtime supplies one process ledger without an OS escape from core.
package splice.upstream.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import splice.core.memory.HeapBudget
import splice.core.util.AsyncFileIo
import splice.core.util.JsonWire

/** JVM adapter for the explicit heap-limit input to the framework-free ledger. */
public object JvmHeap {
    public val limitBytes: Long = Runtime.getRuntime().maxMemory()

    /** Listeners and retained owners share at most half the heap; read views debit this same root. */
    public val budget: HeapBudget = HeapBudget(limitBytes)

    /** Diagnostic-only, off the request path. The control plane calls this on its existing file-I/O dispatcher. */
    public fun memorySnapshot(): JvmMemorySnapshot = JvmMemory.snapshot(budget.retentionSnapshot())

    /** Adds fixed-category process aggregates to an authenticated caller's JSON, on its file-I/O dispatcher. */
    public fun withObservations(payload: String): String {
        val base = Json.parseToJsonElement(payload).jsonObject
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("memory", Json.encodeToJsonElement(memorySnapshot()))
            put("observed_owners", Json.encodeToJsonElement(budget.observationSnapshot()))
            put("file_io", Json.encodeToJsonElement(AsyncFileIo.workSnapshot))
        }.let(JsonWire::string)
    }
}
