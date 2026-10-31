package splice.codemode.nativehost.grpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Internal compatibility envelopes; every native byte is carried by the current protobuf transport. */
internal object NativeGrpcFrames {
    fun response(id: Long, value: JsonObject, initial: Boolean = false): JsonObject = buildJsonObject {
        put("type", if (initial) "execute/initialResponse" else "operation/response")
        put("id", id)
        put(
            "result",
            buildJsonObject {
                put("status", "ok")
                put("value", value)
            },
        )
    }

    fun failed(
        id: Long,
        message: String,
        initial: Boolean = false,
        preserveCell: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("type", if (initial) "execute/initialResponse" else "operation/response")
        put("id", id)
        put(
            "result",
            buildJsonObject {
                put("status", "error")
                put("message", message)
                if (preserveCell) put("preserveCell", true)
            },
        )
    }

    fun started(cell: String): JsonObject = buildJsonObject {
        put("type", "execution/started")
        put("cellId", cell)
    }

    fun delegate(id: String, session: String, request: JsonObject): JsonObject = buildJsonObject {
        put("type", "delegate/request")
        put("id", id)
        put("sessionId", session)
        put("request", request)
    }

    fun cancelled(id: JsonElement): JsonObject = buildJsonObject {
        put("type", "delegate/cancel")
        put("id", id)
    }

    fun closed(session: String, cell: String): JsonObject = buildJsonObject {
        put("type", "cell/closed")
        put("sessionId", session)
        put("cellId", cell)
    }
}
