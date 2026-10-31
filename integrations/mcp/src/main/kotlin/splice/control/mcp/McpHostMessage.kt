package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Only responses and cancellation notifications bypass new-operation admission while draining. */
internal class McpHostMessage(val value: JsonObject, codec: JsonRpcCodec) {
    val kind = codec.kind(value)
    val id = value["id"] ?: JsonPrimitive(0)
    val settlement = kind == RpcKind.RESPONSE ||
        kind == RpcKind.NOTIFICATION && codec.method(value) == "notifications/cancelled"

    fun accepts(admission: McpAdmission?): Boolean = settlement || admission != null
}
