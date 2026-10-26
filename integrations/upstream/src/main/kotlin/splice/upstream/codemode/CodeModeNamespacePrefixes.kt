// PORT-OF: codex-rs/tools/src/indirect_namespace_prefixes.rs @ rust-v0.164.0.
package splice.upstream.codemode

/** Namespace guidance is applied to fresh presentation data after ranking and schema preparation. */
public class CodeModeNamespacePrefixes(
    namespaces: Map<String, String> = emptyMap(),
    servers: Map<String, String> = emptyMap(),
    registeredNamespaces: List<Pair<String, String>> = emptyList(),
) {
    private val prefixes: Map<String, String>

    init {
        val resolved = namespaces.toSortedMap().mapValuesTo(LinkedHashMap()) { (name, prefix) ->
            CodeModeText.trim(prefix) to "namespaces.$name"
        }
        for ((server, namespace) in registeredNamespaces) {
            val prefix = servers[server]?.let(CodeModeText::trim) ?: continue
            val prior = resolved[namespace]
            require(prior == null || prior.first == prefix) {
                "Conflicting indirect description prefixes for namespace `$namespace`: " +
                    "`${prior?.second}` and `mcp_servers.$server`"
            }
            if (prior == null) resolved[namespace] = prefix to "mcp_servers.$server"
        }
        prefixes = resolved.mapValues { it.value.first }.filterValues { it.isNotEmpty() }
    }

    public fun codeMode(tools: List<CodeModeManual.NestedTool>): List<CodeModeManual.NestedTool> = tools.map { tool ->
        val prefix = prefix(tool) ?: return@map tool
        tool.copy(description = prepend(prefix, tool.description))
    }

    public fun prompt(tools: List<CodeModeManual.NestedTool>): List<CodeModeManual.NestedTool> = tools.map { tool ->
        val prefix = prefix(tool) ?: return@map tool
        val namespace = tool.metadata.namespace?.takeIf { it.isNotEmpty() } ?: "functions"
        tool.copy(
            metadata = tool.metadata.copy(
                namespace = namespace,
                namespaceDescription = prepend(prefix, tool.metadata.namespaceDescription.orEmpty()),
            ),
        )
    }

    private fun prefix(tool: CodeModeManual.NestedTool): String? =
        prefixes[tool.metadata.namespace?.takeIf { it.isNotEmpty() } ?: "functions"]

    private fun prepend(prefix: String, description: String): String =
        if (description.isEmpty()) prefix else "$prefix\n\n$description"
}
