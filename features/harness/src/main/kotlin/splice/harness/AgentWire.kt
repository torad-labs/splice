// NEW: the agent's call (docs/specs/harness.md, decision 12) in Anthropic's Messages shape, the one dialect it runs on:
// a request's messages and tools, and a tool call and its result.
package splice.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

internal class AgentWire {
    fun messages(body: JsonObject): List<JsonObject> =
        (body[MESSAGES] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun tools(body: JsonObject): List<JsonObject> =
        (body[TOOLS] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun toolNames(body: JsonObject): Set<String> = tools(body).mapNotNull { JsonScalars.str(it, NAME) }.toSet()

    fun withMessages(body: JsonObject, messages: List<JsonObject>): JsonObject =
        JsonObject(body + (MESSAGES to JsonArray(messages)))

    /** [body] with [tools] after the client's, each one it does not carry yet. */
    fun withTools(body: JsonObject, tools: List<JsonObject>): JsonObject {
        val have = toolNames(body)
        val adding = tools.filter { JsonScalars.str(it, NAME) !in have }.distinctBy { JsonScalars.str(it, NAME) }
        if (adding.isEmpty()) return body
        return JsonObject(body + (TOOLS to JsonArray(tools(body) + adding)))
    }

    fun role(message: JsonObject): String? = JsonScalars.str(message, ROLE)

    /** A message's blocks; text a client sent as a string reads as one text block. */
    fun content(message: JsonObject): List<JsonObject> = when (val content = message[CONTENT]) {
        is JsonArray -> content.mapNotNull { it as? JsonObject }
        is JsonNull, is JsonObject, null -> emptyList()
        is JsonPrimitive -> listOf(buildJsonObject { put(TYPE, TEXT); put(TEXT, content.content) })
    }

    fun message(role: String, blocks: List<JsonObject>): JsonObject = buildJsonObject {
        put(ROLE, role)
        put(CONTENT, JsonArray(blocks))
    }

    fun type(block: JsonObject): String? = JsonScalars.str(block, TYPE)

    fun isCall(block: JsonObject): Boolean = type(block) == TOOL_USE

    fun callName(block: JsonObject): String? = JsonScalars.str(block, NAME)

    fun callId(block: JsonObject): String? = JsonScalars.str(block, ID)

    fun resultFor(block: JsonObject): String? =
        JsonScalars.str(block, TOOL_USE_ID).takeIf { type(block) == TOOL_RESULT }

    /** The result of call [id]: the worker's [text], or what went wrong when [error]. */
    fun result(id: String, text: String, error: Boolean): JsonObject = buildJsonObject {
        put(TYPE, TOOL_RESULT)
        put(TOOL_USE_ID, id)
        put(CONTENT, text)
        if (error) put("is_error", true)
    }
}

internal const val ASSISTANT = "assistant"
internal const val USER = "user"
private const val MESSAGES = "messages"
private const val TOOLS = "tools"
private const val ROLE = "role"
private const val CONTENT = "content"
private const val TYPE = "type"
private const val TEXT = "text"
private const val NAME = "name"
private const val ID = "id"
private const val TOOL_USE = "tool_use"
private const val TOOL_RESULT = "tool_result"
private const val TOOL_USE_ID = "tool_use_id"
