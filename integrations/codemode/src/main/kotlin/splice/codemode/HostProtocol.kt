// NEW: addresses isolate cell results while one framed stream multiplexes the shared host.
package splice.codemode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

internal data class HostFrame(val cell: Long, val request: Long, val payload: JsonObject)

internal object HostProtocol {
    fun frame(cell: Long, request: Long, payload: JsonObject): JsonObject = buildJsonObject {
        put("cell", cell)
        put("request", request)
        put("payload", payload)
    }

    fun parse(frame: JsonObject): HostFrame {
        CodeModeFields.requireKeys(frame, setOf("cell", "request", "payload"))
        val cell = number(frame, "cell")
        val request = number(frame, "request")
        val payload = CodeModeFields.requiredObject(frame["payload"], "host payload must be an object")
        return HostFrame(cell, request, payload)
    }

    fun close(): JsonObject = buildJsonObject { put("type", "close") }

    private fun number(frame: JsonObject, key: String): Long =
        (frame[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
            ?: throw IOException("host $key must be a positive integer")
}
