// NEW: muse-tool-cache (2026-11-16, docs/specs/muse-tool-cache.md, decisions 2 to 4). The caller: the one tool of the
// head's own that a conversation's tool list holds from its first request, on a backend whose prompt cache a changed
// tool list would empty. A tool that arrives later never joins the list. Its definition is written into the
// conversation where it arrived, and the model calls it through the caller, by name. What Meta does with each way of
// handing a tool over is beside MUSE_CALLS_ARRIVED_TOOLS_THROUGH_A_CALLER, in the Muse provider module.
package splice.dialect.responses.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.ToolDefinition

/** A call of the caller, read: the tool it names and the arguments it gives that tool, as JSON text. */
internal data class NamedCall(val tool: String, val arguments: String)

internal class ToolCaller {

    /** The caller as the tool list declares it: a tool's name, a string, and that tool's arguments, an object, both
     *  required and nothing else. This is the shape Meta took and its model used on the real head (2026-11-16). */
    val definition: ToolDefinition = ToolDefinition(
        name = TOOL_CALLER_NAME,
        description = "Calls a tool that arrived during this conversation: one that ToolSearch loaded or a server " +
            "added. Such a tool is not in your tool list. Its definition is written in the conversation where it " +
            "arrived, inside <tool_definition> tags. Pass its name as `$FIELD_TOOL` and its arguments, exactly as " +
            "its definition describes them, as `$FIELD_ARGUMENTS`.",
        inputSchema = buildJsonObject {
            put("type", TYPE_OBJECT)
            put(
                "properties",
                buildJsonObject {
                    put(
                        FIELD_TOOL,
                        buildJsonObject {
                            put("type", TYPE_STRING)
                            put(FIELD_DESCRIPTION, "The tool's name.")
                        },
                    )
                    put(
                        FIELD_ARGUMENTS,
                        buildJsonObject {
                            put("type", TYPE_OBJECT)
                            put(FIELD_DESCRIPTION, "The tool's arguments.")
                            put("additionalProperties", true)
                        },
                    )
                },
            )
            put(
                "required",
                buildJsonArray {
                    add(FIELD_TOOL)
                    add(FIELD_ARGUMENTS)
                },
            )
        },
    )

    /** What tells the model that [tool] is its to call from here on: its name, its description and its schema as the
     *  conversation gave them, and the words that say it is called through the caller. The name is written whole,
     *  since only a name in the tool list is shortened. Nothing here depends on the turn, so it is the same bytes on
     *  every request that carries it. */
    fun written(tool: ToolDefinition): String {
        val definition = buildJsonObject {
            put("name", tool.name)
            tool.description?.let { put(FIELD_DESCRIPTION, it) }
            put("input_schema", tool.inputSchema ?: buildJsonObject { put("type", TYPE_OBJECT) })
        }
        return "${tool.name} is now available. It is not in your tool list: call it through the $TOOL_CALLER_NAME " +
            "tool, with \"${tool.name}\" as `$FIELD_TOOL` and its arguments as `$FIELD_ARGUMENTS`.\n" +
            "<tool_definition>${JsonWire.string(definition)}</tool_definition>"
    }

    /** The arguments of the caller's call of the tool [name] with [input]: how a call Claude Code sends back in the
     *  history goes to the backend again, the way the model made it. */
    fun arguments(name: String, input: JsonObject): String = JsonWire.string(
        buildJsonObject {
            put(FIELD_TOOL, name)
            put(FIELD_ARGUMENTS, input)
        },
    )

    /** The tool a call of the caller names and what it gives it, from the call's [arguments]. A model that leaves the
     *  arguments out gave none, and one that wrote them as JSON text meant the object. Null when the call names no
     *  tool or its arguments are no object: there is no tool to hand that call to. */
    fun named(arguments: String): NamedCall? {
        val call = JsonScalars.objectOrNull(Json, arguments)
        val tool = JsonScalars.str(call, FIELD_TOOL).orEmpty()
        val given = given(call?.get(FIELD_ARGUMENTS))
        return if (tool.isEmpty() || given == null) null else NamedCall(tool, JsonWire.string(given))
    }

    private fun given(arguments: JsonElement?): JsonObject? = when (arguments) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> arguments
        is JsonPrimitive -> arguments.takeIf { it.isString }?.let { JsonScalars.objectOrNull(Json, it.content) }
        is JsonArray -> null
    }
}

/** The caller's name: one no Claude Code tool has, since Claude Code's own are short nouns and verbs and a server's
 *  start with `mcp__`. */
internal const val TOOL_CALLER_NAME = "CallToolByName"

private const val FIELD_TOOL = "tool"
private const val FIELD_ARGUMENTS = "arguments"
private const val FIELD_DESCRIPTION = "description"
