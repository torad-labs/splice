// The JSON-RPC text the front writes, and the field reads it makes, in one place: every message the front builds
// goes through here, so a session and a holder see the same shapes.
package splice.hub.front

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

object Rpc {
    const val ID = "id"
    const val METHOD = "method"
    const val PARAMS = "params"
    const val RESULT = "result"
    const val PROTOCOL = "protocolVersion"
    const val TOOLS_LIST = "tools/list"

    /** The protocol versions the front answers in, newest first. */
    val VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    private val json = Json { encodeDefaults = true }

    fun encode(o: JsonObject): String = json.encodeToString(JsonObject.serializer(), o)

    /** [msg] with one field replaced, as text: how an id is renumbered on its way through. */
    fun with(msg: JsonObject, key: String, value: JsonElement): String = encode(JsonObject(msg + (key to value)))

    fun result(id: JsonElement, result: JsonElement): String =
        encode(
            buildJsonObject {
                put(JSONRPC, VERSION)
                put(ID, id)
                put(RESULT, result)
            },
        )

    fun error(id: JsonElement, words: String): String =
        encode(
            buildJsonObject {
                put(JSONRPC, VERSION)
                put(ID, id)
                put("error", buildJsonObject { put("code", INTERNAL); put("message", words) })
            },
        )

    fun str(o: JsonObject?, key: String): String? = (o?.get(key) as? JsonPrimitive)?.contentOrNull

    /** The words a daemon answer carries in its error field, if it carries any. */
    fun daemonWords(body: String): String? = JsonText.objectOrNull(body)?.let { str(it, "error") }

    private const val JSONRPC = "jsonrpc"
    private const val VERSION = "2.0"
    private const val INTERNAL = -32603
}
