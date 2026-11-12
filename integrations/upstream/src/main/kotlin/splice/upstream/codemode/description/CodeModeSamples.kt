// PORT-OF: codex-rs/code-mode-protocol/src/description.rs @ rust-v0.164.0.
package splice.upstream.codemode.description

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeSchemaTypes
import splice.upstream.codemode.CodeModeText
import splice.upstream.codemode.DEFAULT_INPUT_SCHEMA_MAX_BYTES

/** The same declaration is used by exec's manual, ALL_TOOLS and ranked discovery. */
internal object CodeModeSamples {
    fun render(tool: CodeModeManual.NestedTool): String {
        if (tool.codeModeName == CodeModeManual.TOOL_NAME) return tool.description
        val freeform = tool.metadata.kind == CodeModeManual.Kind.FREEFORM
        val inputName = if (freeform) "input" else "args"
        val inputType = if (freeform) {
            "string"
        } else {
            tool.inputSchema?.let {
                val maxBytes = tool.metadata.inputSchemaMaxBytes ?: DEFAULT_INPUT_SCHEMA_MAX_BYTES
                CodeModeSchemaTypes.render(it, maxBytes)
            } ?: "unknown"
        }
        val global = CodeModeManual.identifier(tool.codeModeName)
        val declaration = "declare const tools: { $global($inputName: $inputType): Promise<${output(
            tool.outputSchema,
        )}>; };"
        return "${tool.description}\n\nexec tool declaration:\n```ts\n$declaration\n```"
    }

    fun withNamespace(tool: CodeModeManual.NestedTool, toolDescriptionFirst: Boolean): String {
        if (tool.metadata.namespace == null) return tool.description
        val namespace = tool.metadata.namespaceDescription?.let(CodeModeText::trim).orEmpty()
        if (namespace.isEmpty()) return tool.description
        return if (toolDescriptionFirst) "${tool.description}\n\n$namespace" else "$namespace\n\n${tool.description}"
    }

    private fun output(schema: JsonElement?): String {
        if (schema == null) return "unknown"
        val structured = structuredContent(schema)
        if (structured == null) return CodeModeSchemaTypes.render(schema)
        val rendered = CodeModeSchemaTypes.render(structured)
        return if (rendered == "unknown") "CallToolResult" else "CallToolResult<$rendered>"
    }

    private fun structuredContent(schema: JsonElement): JsonElement? {
        val properties = (schema as? JsonObject)?.get("properties") as? JsonObject ?: return null
        val content = properties["content"] as? JsonObject ?: return null
        val envelope = JsonScalars.str(content, "type") == "array" && hasType(content["items"], "object") &&
            hasType(properties["isError"], "boolean") && hasType(properties["_meta"], "object")
        return if (envelope) properties["structuredContent"] ?: JsonPrimitive(true) else null
    }

    private fun hasType(schema: JsonElement?, type: String): Boolean =
        JsonScalars.str(schema as? JsonObject, "type") == type
}
