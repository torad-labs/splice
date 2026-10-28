package splice.control.mcp.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

/** Retained catalogs are observations: only a complete, well-formed and unambiguous listing can supply metadata. */
internal object McpCatalogSnapshots {
    fun complete(value: JsonObject): Boolean {
        if (value["nextCursor"]?.let { it != JsonNull } == true) return false
        val tools = value["tools"] as? JsonArray ?: return false
        val names = HashSet<String>()
        return tools.all { completeTool(it, names) }
    }

    fun combine(
        names: Set<String>,
        hosted: Map<String, JsonObject>,
        native: Map<String, JsonObject>,
    ): Map<String, JsonObject> = names.mapNotNull { name ->
        val local = hosted[name]
        val retained = native[name]
        val value = if (local == null) retained else local.takeIf { retained == null || it == retained }
        value?.takeIf(::complete)?.let { name to it }
    }.toMap()

    private fun completeTool(value: JsonElement, names: MutableSet<String>): Boolean {
        val tool = value as? JsonObject ?: return false
        val name = (tool["name"] as? JsonPrimitive)?.takeIf { it.isString }
            ?.let(JsonScalars::str)?.takeIf(String::isNotEmpty) ?: return false
        return names.add(name) && tool["inputSchema"] is JsonObject &&
            listOf("title", "description").all { field -> optionalText(tool, field) }
    }

    private fun optionalText(tool: JsonObject, field: String): Boolean = when (val value = tool[field]) {
        null, JsonNull -> true
        is JsonPrimitive -> value.isString
        else -> false
    }
}
