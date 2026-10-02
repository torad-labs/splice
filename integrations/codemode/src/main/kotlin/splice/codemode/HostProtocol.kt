// NEW: multiplexed cells carry a session address separate from their request and cell identities.
package splice.codemode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

internal data class HostFrame(val cell: Long, val request: Long, val payload: JsonObject, val session: Long = 1)

internal object HostProtocol {
    private const val CELL = "cell"
    private const val HOST_REQUEST_FIELD = "request"
    private const val PAYLOAD = "payload"
    private const val SESSION = "session"

    fun frame(cell: Long, request: Long, payload: JsonObject, session: Long = 1): JsonObject = buildJsonObject {
        put(SESSION, session)
        put(CELL, cell)
        put(HOST_REQUEST_FIELD, request)
        put(PAYLOAD, payload)
    }

    fun parse(frame: JsonObject): HostFrame {
        val keys = setOf(CELL, HOST_REQUEST_FIELD, PAYLOAD)
        CodeModeFields.requireKeys(frame, if (SESSION in frame) keys + SESSION else keys)
        val payload = CodeModeFields.requiredObject(frame[PAYLOAD], "host payload must be an object")
        return HostFrame(
            number(frame, CELL),
            number(frame, HOST_REQUEST_FIELD),
            payload,
            if (SESSION in frame) number(frame, SESSION) else 1,
        )
    }

    fun command(type: String): JsonObject = buildJsonObject { put("type", type) }

    fun count(engines: Int, busy: Int = 0): JsonObject = buildJsonObject {
        put("type", "engines")
        put("count", engines)
        put("busy", busy)
    }

    fun close(): JsonObject = command("close")

    private fun number(frame: JsonObject, key: String): Long =
        (frame[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
            ?: throw IOException("host $key must be a positive integer")
}
