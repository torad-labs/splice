// NEW: V4-390 — the Responses lite tool list codex-rs sends (create_tools_json_for_responses_lite,
// tools/src/tool_spec.rs:95-142, used by core/src/client.rs:893 whenever the provider declares
// namespace_tools, which the OpenAI provider does): every function and custom tool rides inside ONE
// `functions` namespace, placed where the first of them stood, while hosted tools (web_search,
// tool_search) keep their own top-level entries in order. The backend then answers a call with
// namespace "functions" on it, as codex's own history carries it
// (core/tests/common/context_snapshot/context_snapshot_tests.rs:102).
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

/** One rule for grouping a lite tool list and reading it back. The dialect groups every lite turn's
 *  list ([ResponsesLiteShape]); the code-mode bridge reads the members, swaps in its exec surface
 *  and groups the result again, so both paths send the one shape. */
public class ResponsesFunctionNamespace {

    /** [tools] with every function and custom tool moved into the `functions` namespace. An incoming
     *  `functions` namespace is merged into it, keeping its description when it has one, as codex does. */
    public fun group(tools: JsonArray): JsonArray {
        val members = ArrayList<JsonElement>()
        val kept = ArrayList<JsonElement>()
        var description = ""
        var at: Int? = null
        tools.forEach { tool ->
            val item = tool as? JsonObject
            val type = JsonScalars.str(item, NAMESPACE_FIELD_TYPE)
            when {
                type in MEMBER_TYPES -> members += tool
                isFunctions(item) -> {
                    JsonScalars.str(item, NAMESPACE_FIELD_DESCRIPTION)?.takeIf { it.isNotBlank() }?.let { description = it }
                    members += (item?.get(NAMESPACE_FIELD_TOOLS) as? JsonArray).orEmpty()
                }
                else -> {
                    kept += tool
                    return@forEach
                }
            }
            if (at == null) at = kept.size
        }
        val index = at ?: return tools
        if (members.isEmpty()) return JsonArray(kept)
        kept.add(index, namespace(description, members))
        return JsonArray(kept)
    }

    /** The flat list [group] was given: the `functions` namespace's members in its place, every other
     *  entry unchanged. A list with no such namespace reads back as itself. */
    public fun members(tools: JsonArray): List<JsonElement> = tools.flatMap { tool ->
        val item = tool as? JsonObject
        if (isFunctions(item)) (item?.get(NAMESPACE_FIELD_TOOLS) as? JsonArray).orEmpty() else listOf(tool)
    }

    private fun isFunctions(item: JsonObject?): Boolean =
        JsonScalars.str(item, NAMESPACE_FIELD_TYPE) == TYPE_NAMESPACE &&
            JsonScalars.str(item, NAMESPACE_FIELD_NAME) == FUNCTIONS_NAMESPACE

    // Field order is codex's serde order: the enum tag, then ResponsesApiNamespace's name, description, tools.
    private fun namespace(description: String, members: List<JsonElement>): JsonObject = buildJsonObject {
        put(NAMESPACE_FIELD_TYPE, TYPE_NAMESPACE)
        put(NAMESPACE_FIELD_NAME, FUNCTIONS_NAMESPACE)
        put(NAMESPACE_FIELD_DESCRIPTION, description)
        put(NAMESPACE_FIELD_TOOLS, JsonArray(members))
    }
}

/** codex's DEFAULT_FUNCTION_NAMESPACE; its default description is empty (tools/src/responses_api.rs:64-70). */
private const val FUNCTIONS_NAMESPACE = "functions"
private const val TYPE_NAMESPACE = "namespace"
private const val NAMESPACE_FIELD_TYPE = "type"
private const val NAMESPACE_FIELD_NAME = "name"
private const val NAMESPACE_FIELD_DESCRIPTION = "description"
private const val NAMESPACE_FIELD_TOOLS = "tools"

/** ResponsesApiNamespaceTool's two variants: Function ("function") and Custom ("custom"). */
private val MEMBER_TYPES = setOf("function", "custom")
