// PORT-OF: codex-rs/tools/src/tool_search.rs @ rust-v0.164.0.
package splice.upstream.codemode.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeText

/** A search source can expose several callable tools; ranking precedes per-tool policy and the result limit. */
public data class CodeModeSearchEntry(val tools: List<CodeModeManual.NestedTool>, val searchText: String)

public object CodeModeSearchCorpus {
    public fun entries(
        tools: List<CodeModeManual.NestedTool>,
        mcp: Map<String, CodeModeMcpSearchInfo> = emptyMap(),
    ): List<CodeModeSearchEntry> {
        val namespaces = tools.filter { it.metadata.namespace != null && it.codeModeName !in mcp }
            .groupBy { it.metadata.namespace }
        val seen = HashSet<String>()
        return tools.mapNotNull { tool ->
            val namespace = tool.metadata.namespace
            val custom = mcp[tool.codeModeName]
            if (custom != null) {
                require(
                    custom.declarationName == tool.codeModeName,
                ) { "MCP search identity differs from its advertised tool" }
                CodeModeSearchEntry(listOf(tool), CodeModeMcpSearchText.build(custom))
            } else if (namespace == null) {
                entry(tool)
            } else if (seen.add(namespace)) {
                val members = namespaces.getValue(namespace)
                CodeModeSearchEntry(members, text(members))
            } else {
                null
            }
        }
    }

    public fun entry(tool: CodeModeManual.NestedTool): CodeModeSearchEntry =
        CodeModeSearchEntry(listOf(tool), text(listOf(tool)))

    public fun text(tools: List<CodeModeManual.NestedTool>): String {
        val parts = ArrayList<String>()
        val first = tools.firstOrNull()
        first?.metadata?.namespace?.let { push(parts, it) }
        first?.metadata?.namespaceDescription?.let { push(parts, it) }
        for (tool in tools) {
            push(parts, tool.name)
            if (tool.metadata.kind == CodeModeManual.Kind.FUNCTION) push(parts, tool.name.replace('_', ' '))
            push(parts, tool.description)
            if (tool.metadata.kind == CodeModeManual.Kind.FUNCTION) {
                appendSchema(tool.inputSchema, parts)
            } else {
                tool.metadata.freeformSyntax?.let { push(parts, it) }
            }
        }
        return parts.joinToString(" ")
    }

    private fun appendSchema(schema: JsonElement?, parts: MutableList<String>) {
        val objectSchema = schema as? JsonObject ?: return
        JsonScalars.str(objectSchema, "description")?.let { push(parts, it) }
        (objectSchema["properties"] as? JsonObject)?.entries?.sortedWith(
            compareBy(CodeModeText.order) {
                it.key
            },
        )?.forEach { (name, property) ->
            push(parts, name)
            appendSchema(property, parts)
        }
        objectSchema["items"]?.let { appendSchema(it, parts) }
        (objectSchema["anyOf"] as? JsonArray)?.forEach { appendSchema(it, parts) }
    }

    private fun push(parts: MutableList<String>, part: String) {
        val trimmed = CodeModeText.trim(part)
        if (trimmed.isNotEmpty()) parts += trimmed
    }
}
