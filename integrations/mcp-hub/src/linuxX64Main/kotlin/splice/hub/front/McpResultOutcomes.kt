package splice.hub.front

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Only real typed MCP packets enter the shared carrier; client text and malformed replies have no raw outcome. */
internal object McpResultOutcomes {
    fun fromReply(answer: JsonObject): JsonObject? {
        val result = answer[Rpc.RESULT] as? JsonObject
        val error = answer[RPC_ERROR] as? JsonObject
        return when {
            (Rpc.RESULT in answer) == (RPC_ERROR in answer) -> null
            result != null -> rawResult(result)
            error != null -> rpcError(error)
            else -> null
        }
    }

    private fun rawResult(result: JsonObject): JsonObject? =
        if (result["content"] is JsonArray) envelope("RawResult", "value", result) else null

    private fun rpcError(error: JsonObject): JsonObject? {
        val code = error["code"] as? JsonPrimitive
        val message = error["message"] as? JsonPrimitive
        return when {
            code == null || code.isString -> null
            code.intOrNull == null -> null
            message?.isString != true -> null
            else -> envelope("RpcError", RPC_ERROR, error)
        }
    }

    private fun envelope(type: String, key: String, value: JsonObject): JsonObject = buildJsonObject {
        put("type", type)
        put(key, value)
    }
}

private const val RPC_ERROR = "error"
