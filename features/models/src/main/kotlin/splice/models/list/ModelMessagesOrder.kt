package splice.models.list

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.core.util.text.UnicodeOrder

/** Known messages follow the typed catalog's null and declaration rules; unknown fields retain their values. */
internal object ModelMessagesOrder {
    private val model = listOf(
        "content_filter_guidance", "persistent_instructions", "tools", "instructions_template",
        "approvals", "collaboration_modes", "auto_review", "permissions", "multi_agent",
        "token_budget", "guardian_v2", "confirmation_policies",
    )
    private val tools = listOf(
        "history", "notes", "context_management", "incremental_tools",
        "functions_namespace_functions_description_prefixes", "indirect_description_prefixes",
        "send_user_message_async", "multi_agent", "code_mode", "mcp_resources",
    )
    private val codeMode = listOf("exec", "wait", "deferred_nested_tools_guidance", "mcp_typescript_preamble")
    private val message = listOf("description", "parameters")
    private val required = setOf(
        "instructions_template",
        "approvals",
        "collaboration_modes",
        "auto_review",
        "permissions",
        "multi_agent",
    )
    private val indirect = listOf("namespaces", "mcp_servers")

    fun ordered(value: JsonObject?): JsonObject? = value?.let {
        ordered(it, model, CatalogMessageChild(::modelChild), required)
    }

    private fun modelChild(name: String, value: JsonElement): JsonElement =
        if (name == "tools" && value is JsonObject) ordered(value, tools, CatalogMessageChild(::toolChild)) else value

    private fun toolChild(name: String, value: JsonElement): JsonElement {
        if (value !is JsonObject) return value
        return when (name) {
            "code_mode" -> ordered(value, codeMode, CatalogMessageChild(::codeModeChild))
            "functions_namespace_functions_description_prefixes" -> sorted(value)
            "indirect_description_prefixes" -> ordered(
                value,
                indirect,
                CatalogMessageChild { _, element ->
                    if (element is JsonObject) sorted(element) else element
                },
            )
            else -> value
        }
    }

    private fun sorted(value: JsonObject): JsonObject = JsonObject(value.toSortedMap(UnicodeOrder.scalarValues))

    private fun codeModeChild(name: String, value: JsonElement): JsonElement =
        if (name in setOf("exec", "wait") && value is JsonObject) ordered(value, message) else value

    private fun ordered(
        value: JsonObject,
        fields: List<String>,
        child: CatalogMessageChild = CatalogMessageChild { _, element -> element },
        alwaysPresent: Set<String> = emptySet(),
    ): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        for (name in fields) {
            val element = value[name] ?: JsonNull
            if (element !== JsonNull || name in alwaysPresent) result[name] = child.ordered(name, element)
        }
        for ((name, element) in value) if (name !in fields) result[name] = element
        return JsonObject(result)
    }
}

private fun interface CatalogMessageChild {
    fun ordered(name: String, value: JsonElement): JsonElement
}
