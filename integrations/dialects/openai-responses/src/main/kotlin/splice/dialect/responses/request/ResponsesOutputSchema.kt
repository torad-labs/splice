// NEW: caller JSON output contracts, distinct from function-tool input strictness.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.wire.AnthropicRequest

/** Claude Code's schema tool: returned schema text must reach the client as this tool's JSON input. */
internal const val STRUCTURED_OUTPUT_TOOL = "StructuredOutput"

// why: the same schema field is validated, compared with the tool contract, and forwarded unchanged.
private const val SCHEMA_KEY = "schema"

/** The caller's output schema, forwarded unchanged so the caller's own validation retains its meaning. */
internal object ResponsesOutputSchema {
    private val choices = setOf("auto", "none", "any", "required", "tool")

    /** A forced schema tool can be enforced by schema-only text, then translated back to its tool input. */
    fun forcesTool(body: AnthropicRequest): Boolean = when (body.toolChoice?.type) {
        "tool" -> body.toolChoice?.name == STRUCTURED_OUTPUT_TOOL
        "any", "required" -> body.tools.size == 1 && body.tools.first().name == STRUCTURED_OUTPUT_TOOL
        else -> false
    }

    fun wireBody(body: AnthropicRequest, format: JsonObject?): AnthropicRequest =
        if (format != null && forcesTool(body)) body.copy(tools = emptyList(), toolChoice = null) else body

    /** A declared schema tool cannot override the caller's explicit prohibition on tool calls. */
    fun toolName(body: AnthropicRequest): String? =
        if (body.toolChoice?.type == "none") {
            null
        } else {
            body.tools.firstOrNull { it.name == STRUCTURED_OUTPUT_TOOL && it.inputSchema != null }?.name
        }

    /** Checked before request construction, through the provider's explicit admission refusal. */
    fun refusal(body: AnthropicRequest, raw: JsonObject): String? {
        val native = nativeFormat(raw)
        return nativeRefusal(native) ?: toolRefusal(body, native) ?: choiceRefusal(body)
    }

    private fun nativeRefusal(native: JsonElement?): String? = when {
        native == null -> null
        native !is JsonObject -> "Responses output format must be a JSON object"
        JsonScalars.str(native, "type") != "json_schema" -> "Responses structured output requires a json_schema format"
        native[SCHEMA_KEY] !is JsonObject -> "Responses json_schema format requires a schema object"
        else -> null
    }

    private fun toolRefusal(body: AnthropicRequest, native: JsonElement?): String? {
        val tool = body.tools.firstOrNull { it.name == STRUCTURED_OUTPUT_TOOL } ?: return null
        return when {
            tool.inputSchema == null -> "StructuredOutput requires an input_schema"
            native is JsonObject && tool.inputSchema != native[SCHEMA_KEY] ->
                "StructuredOutput input_schema and output format schema disagree"
            else -> null
        }
    }

    private fun choiceRefusal(body: AnthropicRequest): String? {
        val choice = body.toolChoice ?: return null
        return when (choice.type) {
            "tool" ->
                if (body.tools.any { it.name == choice.name }) null else "The selected tool must name a declared tool"
            "any", "required" -> if (body.tools.isEmpty()) "Required tool choice needs a declared tool" else null
            else -> if (choice.type in choices) null else "Unsupported Responses tool choice"
        }
    }

    private fun nativeFormat(raw: JsonObject): JsonElement? =
        (raw["output_config"] as? JsonObject)?.get("format") ?: raw["output_format"]

    fun format(body: AnthropicRequest, raw: JsonObject): JsonObject? {
        val native = nativeFormat(raw) as? JsonObject
        val tool = body.tools.firstOrNull { it.name == STRUCTURED_OUTPUT_TOOL }
        val schema = (native?.get(SCHEMA_KEY) as? JsonObject) ?: tool?.inputSchema ?: return null
        return buildJsonObject {
            put("type", "json_schema")
            put("name", JsonScalars.str(native, "name") ?: "structured_output")
            put(SCHEMA_KEY, schema)
            put("strict", true)
        }
    }
}
