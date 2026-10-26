// PORT-OF: codex-rs/tools/src/code_mode.rs @ rust-v0.164.0.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

/** Collect raw function, freeform, namespace and search declarations; hosted web search stays outside exec. */
public object CodeModeToolDefinitions {
    public val emptyInputSchema: JsonObject = buildJsonObject { put("type", "object") }

    public fun collect(specs: List<JsonElement>): List<CodeModeManual.NestedTool> = specs
        .flatMap { collect(it as? JsonObject) }
        .filter { it.codeModeName != CodeModeManual.TOOL_NAME && it.codeModeName != "wait" }
        .sortedWith(compareBy(CodeModeText.order) { it.codeModeName })
        .distinctBy { it.codeModeName }

    private fun collect(spec: JsonObject?): List<CodeModeManual.NestedTool> = when (JsonScalars.str(spec, "type")) {
        "function", "custom" -> listOfNotNull(spec?.let { definition(it, CodeModeManual.Metadata()) })
        "namespace" -> namespace(checkNotNull(spec))
        "tool_search" -> listOf(CodeModeToolSearch.definition(spec?.get("parameters")?.let(::parameters)))
        else -> emptyList()
    }

    private fun namespace(spec: JsonObject): List<CodeModeManual.NestedTool> {
        val name = JsonScalars.str(spec, "name")
        // Codex's default namespace (DEFAULT_FUNCTION_NAMESPACE, codex-rs/protocol/src/tool_name.rs) holds plain
        // functions, which the wire only groups, so its members read as the flat list's do and the manual sorts them
        // the same way. codeModeName already reads the namespace as none.
        val metadata = if (name == CodeModeManual.DEFAULT_FUNCTION_NAMESPACE) {
            CodeModeManual.Metadata()
        } else {
            CodeModeManual.Metadata(namespace = name, namespaceDescription = JsonScalars.str(spec, "description"))
        }
        return (spec["tools"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.let { tool ->
                definition(tool, metadata)
            }
        }
    }

    private fun definition(spec: JsonObject, metadata: CodeModeManual.Metadata): CodeModeManual.NestedTool? {
        val kind = when (JsonScalars.str(spec, "type")) {
            "function" -> CodeModeManual.Kind.FUNCTION
            "custom" -> CodeModeManual.Kind.FREEFORM
            else -> return null
        }
        val name = JsonScalars.str(spec, "name") ?: return null
        return CodeModeManual.NestedTool(
            name,
            JsonScalars.str(spec, "description").orEmpty(),
            if (kind == CodeModeManual.Kind.FUNCTION) parameters(spec["parameters"] ?: emptyInputSchema) else null,
            spec["output_schema"].takeIf { kind == CodeModeManual.Kind.FUNCTION },
            metadata.copy(kind = kind, freeformSyntax = JsonScalars.str(spec["format"] as? JsonObject, "syntax")),
        )
    }

    public fun parameters(value: JsonElement): JsonObject = requireNotNull(CodeModeSchemaProjection.canonical(value)) {
        "tool parameters use unsupported JSON Schema structures"
    }
}
