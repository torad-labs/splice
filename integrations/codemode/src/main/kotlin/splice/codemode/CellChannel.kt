// NEW: a cell owns a protocol address, not the process that hosts its isolated context.
package splice.codemode

import kotlinx.serialization.json.JsonObject

internal interface CellChannel : AutoCloseable {
    suspend fun exchange(frame: JsonObject): JsonObject
    fun afterExit(action: WorkerExited)
}
