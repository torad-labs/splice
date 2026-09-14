// NEW: v0.4.0 FEATURES.md §8 — the JSON-RPC 2.0 shapes the MCP host reads and writes. Kept
// separate from transport and process code so id remapping — the one place a bug silently
// hands session A's answer to session B — is a handful of pure functions with their own tests.
package splice.control.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ID = "id"
private const val METHOD = "method"
private const val PARAMS = "params"
private const val VERSION_KEY = "jsonrpc"
private const val VERSION = "2.0"

/** What a parsed message is; the host branches on this once and never re-inspects the object. */
internal enum class RpcKind { REQUEST, NOTIFICATION, RESPONSE, INVALID }

internal class JsonRpcCodec(private val json: Json = Json { ignoreUnknownKeys = true }) {

    fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encode(obj: JsonObject): String = json.encodeToString(JsonObject.serializer(), obj)

    fun kind(msg: JsonObject): RpcKind {
        val hasId = msg.containsKey(ID) && msg[ID] !is JsonNull
        val hasMethod = msg[METHOD] is JsonPrimitive
        return when {
            hasMethod && hasId -> RpcKind.REQUEST
            hasMethod -> RpcKind.NOTIFICATION
            hasId && (msg.containsKey("result") || msg.containsKey("error")) -> RpcKind.RESPONSE
            else -> RpcKind.INVALID
        }
    }

    fun method(msg: JsonObject): String = (msg[METHOD] as? JsonPrimitive)?.content.orEmpty()

    /** The message with its `id` replaced — the only mutation the host ever performs on a request. */
    fun withId(msg: JsonObject, id: JsonElement): JsonObject = buildJsonObject {
        msg.forEach { (k, v) -> if (k != ID) put(k, v) }
        put(ID, id)
    }

    /** `notifications/cancelled` carries the request id inside params; it must travel remapped too. */
    fun withCancelledRequestId(msg: JsonObject, id: JsonElement): JsonObject {
        val params = msg[PARAMS] as? JsonObject ?: return msg
        val remapped = buildJsonObject {
            params.forEach { (k, v) -> if (k != "requestId") put(k, v) }
            put("requestId", id)
        }
        return buildJsonObject {
            msg.forEach { (k, v) -> if (k != PARAMS) put(k, v) }
            put(PARAMS, remapped)
        }
    }

    fun result(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put(VERSION_KEY, VERSION)
        put(ID, id)
        put("result", result)
    }

    fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put(VERSION_KEY, VERSION)
        put(ID, id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

    fun request(id: JsonElement, method: String, params: JsonObject): JsonObject = buildJsonObject {
        put(VERSION_KEY, VERSION)
        put(ID, id)
        put(METHOD, method)
        put(PARAMS, params)
    }

    fun notification(method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put(VERSION_KEY, VERSION)
        put(METHOD, method)
        if (params != null) put(PARAMS, params)
    }
}
