package splice.models.list

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Keep catalog message values losslessly while using the typed catalog's declaration order. */
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

    fun ordered(value: JsonObject?): JsonObject? = value?.let { ordered(it, model, CatalogMessageChild(::modelChild)) }

    private fun modelChild(name: String, value: JsonElement): JsonElement =
        if (name == "tools" && value is JsonObject) ordered(value, tools, CatalogMessageChild(::toolChild)) else value

    private fun toolChild(name: String, value: JsonElement): JsonElement =
        if (name == "code_mode" && value is JsonObject) {
            ordered(
                value,
                codeMode,
                CatalogMessageChild(::codeModeChild),
            )
        } else {
            value
        }

    private fun codeModeChild(name: String, value: JsonElement): JsonElement =
        if (name in setOf("exec", "wait") && value is JsonObject) ordered(value, message) else value

    private fun ordered(
        value: JsonObject,
        fields: List<String>,
        child: CatalogMessageChild = CatalogMessageChild { _, element -> element },
    ): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        for (name in fields) value[name]?.let { result[name] = child.ordered(name, it) }
        for ((name, element) in value) if (name !in result) result[name] = element
        return JsonObject(result)
    }
}

private fun interface CatalogMessageChild {
    fun ordered(name: String, value: JsonElement): JsonElement
}
