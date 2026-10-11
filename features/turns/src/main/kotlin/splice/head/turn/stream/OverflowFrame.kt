// NEW: V4-446 — inspect the emitter's typed error frame before committing HTTP status.
package splice.head.turn.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.util.ERR_SNIPPET
import splice.core.wire.ErrorEnvelope

/** Decoding belongs at the wire boundary; the stream gate owns only the status decision. */
internal object OverflowFrame {
    fun body(frame: String): String? {
        if (!frame.startsWith("event: error\n")) return null
        val payload = frame.substringAfter("data: ", "").substringBefore("\n\n")
        val error = try {
            Json.parseToJsonElement(payload).jsonObject["error"]?.jsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        val message = if (error?.get("type")?.jsonPrimitive?.content == "invalid_request_error") {
            error["message"]?.jsonPrimitive?.content
        } else {
            null
        }
        val index = message?.indexOf("prompt is too long", ignoreCase = true) ?: -1
        val overflow = message?.takeIf { index >= 0 }?.substring(index)?.take(ERR_SNIPPET) ?: return null
        return ErrorEnvelope.of("invalid_request_error", overflow).toString()
    }
}
