package splice.provider.codex.catalog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.upstream.codemode.CodeModeFunctionPrefixes
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeNamespacePrefixes

/** Only the Code Mode families are selected from the model's sparse catalog messages. */
internal class CodeModeCatalogMessages(messages: JsonObject?) {
    private val tools = messages?.get("tools") as? JsonObject
    private val codeMode = tools?.get("code_mode") as? JsonObject
    private val indirect = tools?.get("indirect_description_prefixes") as? JsonObject
    val namespacePrefixes = CodeModeNamespacePrefixes(
        strings(indirect?.get("namespaces") as? JsonObject),
        strings(indirect?.get("mcp_servers") as? JsonObject),
    )
    val exec: CodeModeManual.Messages = CodeModeManual.Messages(
        execDescription = string(codeMode?.get("exec") as? JsonObject, "description"),
        deferredGuidance = string(codeMode, "deferred_nested_tools_guidance"),
        mcpPreamble = string(codeMode, "mcp_typescript_preamble"),
    )
    val waitDescription: String? = string(codeMode?.get("wait") as? JsonObject, "description")
    val waitParameters: String? = string(codeMode?.get("wait") as? JsonObject, "parameters")

    fun functions(definitions: List<CodeModeManual.NestedTool>): List<CodeModeManual.NestedTool> =
        CodeModeFunctionPrefixes(
            strings(tools?.get("functions_namespace_functions_description_prefixes") as? JsonObject),
            definitions,
        )
            .apply(definitions)

    private fun strings(value: JsonObject?): Map<String, String> = value.orEmpty().mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
    }.toMap()

    private fun string(value: JsonObject?, key: String): String? =
        (value?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
