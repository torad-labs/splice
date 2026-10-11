// NEW: multiplexed cells carry a session address separate from their request and cell identities.
package splice.codemode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

internal data class HostFrame(
    val cell: Long,
    val request: Long,
    val payload: JsonObject,
    val session: Long = 1,
    val replyKey: String? = null,
)

internal object HostProtocol {
    private const val CELL = "cell"
    private const val HOST_REQUEST_FIELD = "request"
    private const val PAYLOAD = "payload"
    private const val SESSION = "session"
    private const val REPLY_KEY = "reply_key"

    fun frame(
        cell: Long,
        request: Long,
        payload: JsonObject,
        session: Long = 1,
        replyKey: String? = null,
    ): JsonObject = buildJsonObject {
        put(SESSION, session)
        put(CELL, cell)
        put(HOST_REQUEST_FIELD, request)
        put(PAYLOAD, payload)
        replyKey?.let { put(REPLY_KEY, it) }
    }

    fun reply(request: HostFrame, payload: JsonObject): JsonObject =
        frame(request.cell, request.request, payload, request.session, request.replyKey)

    fun parse(frame: JsonObject): HostFrame {
        val keys = setOf(CELL, HOST_REQUEST_FIELD, PAYLOAD) +
            setOf(SESSION, REPLY_KEY).filter { it in frame }
        CodeModeFields.requireKeys(frame, keys)
        val payload = CodeModeFields.requiredObject(frame[PAYLOAD], "host payload must be an object")
        return HostFrame(
            number(frame, CELL),
            number(frame, HOST_REQUEST_FIELD),
            payload,
            if (SESSION in frame) number(frame, SESSION) else 1,
            if (REPLY_KEY in frame) CodeModeFields.requiredString(frame, REPLY_KEY) else null,
        )
    }

    fun command(type: String): JsonObject = buildJsonObject { put("type", type) }

    fun count(engines: Int, busy: Int = 0): JsonObject = buildJsonObject {
        put("type", "engines")
        put("count", engines)
        put("busy", busy)
    }

    fun capacity(): JsonObject = buildJsonObject {
        put("type", "capacity")
        put(
            "detail",
            "Code-mode host cap: ${CodeModeHeap.maxEnginesPerHost} engines; " +
                "adjust quirks.code_mode_workers or quirks.code_mode_memory_mb",
        )
    }

    fun close(): JsonObject = command("close")

    private fun number(frame: JsonObject, key: String): Long =
        (frame[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
            ?: throw IOException("host $key must be a positive integer")
}
