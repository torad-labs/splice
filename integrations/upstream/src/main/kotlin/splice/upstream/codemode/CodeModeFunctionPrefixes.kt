// PORT-OF: codex-rs/tools/src/functions_namespace_function_prefixes.rs @ rust-v0.164.0.
package splice.upstream.codemode

/** Trimmed prefixes affect available default-namespace tools before indexing or declaration rendering. */
public class CodeModeFunctionPrefixes(prefixes: Map<String, String>, available: List<CodeModeManual.NestedTool>) {
    private val prepared: Map<String, String>

    init {
        var remaining = FUNCTION_PREFIX_BYTES
        val selected = LinkedHashMap<String, String>()
        for (tool in available) {
            if (defaultNamespace(tool) && tool.name !in selected) {
                val prefix = prefixes[tool.name]?.let(CodeModeText::trim)?.takeIf { it.isNotEmpty() } ?: continue
                remaining -= prefix.toByteArray(Charsets.UTF_8).size + PARAGRAPH_SEPARATOR_BYTES
                require(
                    remaining >= 0,
                ) { "Available functions' description prefixes exceed the 256-byte combined limit" }
                selected[tool.name] = prefix
            }
        }
        prepared = selected
    }

    /** Empty namespaces count toward the source budget but keep their original descriptions. */
    public fun apply(tools: List<CodeModeManual.NestedTool>): List<CodeModeManual.NestedTool> = tools.map { tool ->
        val prefix = prepared[tool.name]?.takeIf {
            tool.metadata.namespace != "" && defaultNamespace(
                tool,
            ) && tool.codeModeName != CodeModeToolSearch.FUNCTION_NAME
        }
            ?: return@map tool
        tool.copy(description = if (tool.description.isEmpty()) prefix else "$prefix\n\n${tool.description}")
    }

    private fun defaultNamespace(tool: CodeModeManual.NestedTool): Boolean =
        tool.metadata.namespace == null || tool.metadata.namespace in setOf("", "functions")
}

// why: Codex 0.164 gives the default-namespace prefixes one 256-byte combined budget,
// `remaining = 256usize` in functions_namespace_function_prefixes.rs.
private const val FUNCTION_PREFIX_BYTES = 256

// why: Codex joins each prefix to its description with "\n\n" and charges those 2 bytes to the budget.
private const val PARAGRAPH_SEPARATOR_BYTES = 2
