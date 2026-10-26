// Codex's full native EXEC_DESCRIPTION_TEMPLATE, with its default yield budget and image helper rendered.
// All advertised globals execute in the bundled upstream host; this is also the manual for client tools.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonElement
import splice.upstream.codemode.description.CodeModeDescriptions
import splice.upstream.codemode.description.CodeModeSamples

public object CodeModeManual {
    /** codex-rs code-mode-protocol PUBLIC_TOOL_NAME. */
    public const val TOOL_NAME: String = "exec"

    /** codex-rs protocol DEFAULT_FUNCTION_NAMESPACE: a tool in it is a plain function, named without a prefix. */
    public const val DEFAULT_FUNCTION_NAMESPACE: String = "functions"
    public const val DEFAULT_EXEC_YIELD_TIME_MS: Long = splice.core.config.DEFAULT_CODE_MODE_EXEC_YIELD_MS

    /** codex-rs execute_spec.rs CODE_MODE_FREEFORM_GRAMMAR, byte for byte. */
    public const val GRAMMAR: String = "\nstart: pragma_source | plain_source\n" +
        "pragma_source: PRAGMA_LINE NEWLINE SOURCE\nplain_source: SOURCE\n\n" +
        "PRAGMA_LINE: /[ \\t]*\\/\\/ @exec:[^\\r\\n]*/\nNEWLINE: /\\r?\\n/\nSOURCE: /[\\s\\S]+/\n"

    /** Function tools receive objects; freeform tools receive their unmodified string input. */
    public enum class Kind { FUNCTION, FREEFORM }

    public enum class Discovery { CATALOG, RANKED_SEARCH }

    /** Metadata retained separately from a tool's stable callable name and schemas. */
    public data class Metadata(
        val kind: Kind = Kind.FUNCTION,
        val namespace: String? = null,
        val namespaceDescription: String? = null,
        val inputSchemaMaxBytes: Int? = null,
        val codeModeInputSchemaMaxBytes: Int? = null,
        val freeformSyntax: String? = null,
    )

    /** A raw tool definition. [codeModeName] also preserves Codex's namespace underscore edge cases. */
    public data class NestedTool(
        val name: String,
        val description: String,
        val inputSchema: JsonElement?,
        val outputSchema: JsonElement? = null,
        val metadata: Metadata = Metadata(),
    ) {
        val codeModeName: String get() = CodeModeManual.codeModeName(name, metadata.namespace)
    }

    /** Model-owned optional text replaces only its corresponding section; empty strings suppress it. */
    public data class Messages(
        val execDescription: String? = null,
        val deferredGuidance: String? = null,
        val mcpPreamble: String? = null,
    )

    public data class DescriptionOptions(
        val defaultYieldTimeMs: Long = DEFAULT_EXEC_YIELD_TIME_MS,
        val codeModeOnly: Boolean = true,
        val imageDetailVisible: Boolean = true,
        val discovery: Discovery = Discovery.CATALOG,
        val messages: Messages = Messages(),
    )

    /** Current build_exec_tool_description, including guidance and MCP types for an empty catalog. */
    public fun description(
        enabled: List<NestedTool>,
        deferred: Boolean,
        options: DescriptionOptions = DescriptionOptions(),
    ): String = CodeModeDescriptions.description(enabled, deferred, options)

    /** The nested tools in name order, the first of any two that normalize to one identifier kept —
     *  the manual and the runtime's `tools` object both come from this one rule. */
    public fun nestedNames(names: Collection<String>): List<String> = names.sortedWith(CodeModeText.order)
        .filter { it != TOOL_NAME && it != "wait" }
        .distinctBy(::identifier)

    public fun codeModeName(name: String, namespace: String?): String = when (namespace) {
        null, "", DEFAULT_FUNCTION_NAMESPACE -> name
        else -> if (namespace.endsWith('_') || name.startsWith('_')) "$namespace$name" else "${namespace}__$name"
    }

    /** normalize_code_mode_identifier: every code point outside `[A-Za-z_$][A-Za-z0-9_$]*` becomes `_`. */
    public fun identifier(toolKey: String): String {
        val identifier = StringBuilder()
        toolKey.codePoints().toArray().forEachIndexed { index, point ->
            val letter = point == '_'.code || point == '$'.code || point in 'a'.code..'z'.code ||
                point in 'A'.code..'Z'.code
            val valid = letter || (index > 0 && point in '0'.code..'9'.code)
            identifier.append(if (valid) Character.toString(point) else "_")
        }
        return identifier.ifEmpty { "_" }.toString()
    }

    /** augment_tool_definition: the description `ALL_TOOLS` carries — the tool's own description, then
     *  its TypeScript declaration, so a deferred tool the manual omits still shows its arguments. */
    public fun augmented(tool: NestedTool): String = CodeModeSamples.render(tool)

    public fun waitDescription(): String {
        val wait = CodeModeDescriptions.wait.trim()
        return "Waits on a yielded `$TOOL_NAME` cell and returns new output or completion.\n$wait"
    }

    public fun withInputBudget(tool: NestedTool, codeModeMaxBytes: Int?): NestedTool = tool.copy(
        metadata = tool.metadata.copy(
            inputSchemaMaxBytes = maxOf(
                DEFAULT_INPUT_SCHEMA_MAX_BYTES,
                tool.metadata.inputSchemaMaxBytes ?: 0,
                codeModeMaxBytes ?: 0,
            ),
        ),
    )

    /** Runtime catalogs carry augmented descriptions, then drop schemas already rendered into them. */
    public fun prepare(tools: List<NestedTool>, toolDescriptionFirst: Boolean = false): List<NestedTool> = tools
        .filter { it.codeModeName != TOOL_NAME && it.codeModeName != "wait" }
        .map { tool ->
            val description = CodeModeSamples.withNamespace(tool, toolDescriptionFirst)
            val definition = withInputBudget(
                tool,
                tool.metadata.codeModeInputSchemaMaxBytes,
            ).copy(description = description)
            tool.copy(description = augmented(definition), inputSchema = null, outputSchema = null)
        }
        .sortedWith(compareBy(CodeModeText.order, NestedTool::codeModeName))
        .distinctBy(NestedTool::codeModeName)
}
