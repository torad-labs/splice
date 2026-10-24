// Codex's full native EXEC_DESCRIPTION_TEMPLATE, with its default yield budget and image helper rendered.
// All advertised globals execute in the bundled upstream host; this is also the manual for client tools.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonElement

public object CodeModeManual {
    /** codex-rs code-mode-protocol PUBLIC_TOOL_NAME. */
    public const val TOOL_NAME: String = "exec"

    /** codex-rs execute_spec.rs CODE_MODE_FREEFORM_GRAMMAR, byte for byte. */
    public const val GRAMMAR: String = "\nstart: pragma_source | plain_source\n" +
        "pragma_source: PRAGMA_LINE NEWLINE SOURCE\nplain_source: SOURCE\n\n" +
        "PRAGMA_LINE: /[ \\t]*\\/\\/ @exec:[^\\r\\n]*/\nNEWLINE: /\\r?\\n/\nSOURCE: /[\\s\\S]+/\n"

    private const val DEFERRED_GUIDANCE: String =
        "Some deferred nested tools may be omitted from this description. They are still available on the " +
            "global `tools` object and listed in `ALL_TOOLS`.\n" +
            "To find one, filter `ALL_TOOLS` by `name` and `description`."

    private val template: String = checkNotNull(javaClass.getResourceAsStream("exec-description.txt")) {
        "missing bundled exec manual template"
    }.bufferedReader(Charsets.UTF_8).use { it.readText().trimEnd() }

    /** One nested tool as the manual renders it: its client name, description and argument schema. */
    public data class NestedTool(val name: String, val description: String, val inputSchema: JsonElement?)

    /** build_exec_tool_description(code_mode_only = true): the template, the deferred-tool note when the
     *  surface withheld tools, then one section per tool in name order. */
    public fun description(enabled: List<NestedTool>, deferred: Boolean): String {
        // No streaming paragraph: a script runs only once its whole item has arrived (CodeModeSourceBuffer), so the
        // model writes ordinary JavaScript, as Codex's manual tells it to, with no names it may not redeclare.
        val sections = mutableListOf(template)
        if (deferred) sections += DEFERRED_GUIDANCE
        val byName = enabled.associateBy(NestedTool::name)
        val nested = nestedNames(byName.keys).map { section(byName.getValue(it)) }
        if (nested.isNotEmpty()) sections += nested.joinToString("\n\n")
        return sections.joinToString("\n\n")
    }

    /** The nested tools in name order, the first of any two that normalize to one identifier kept —
     *  the manual and the runtime's `tools` object both come from this one rule. */
    public fun nestedNames(names: Collection<String>): List<String> = names.sorted().distinctBy(::identifier)

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
    public fun augmented(tool: NestedTool): String = sample(tool, identifier(tool.name))

    private fun section(tool: NestedTool): String {
        val global = identifier(tool.name)
        val heading = if (global == tool.name) "### `$global`" else "### `$global` (`${tool.name}`)"
        val sample = sample(tool, global).trim()
        return if (sample.isEmpty()) heading else "$heading\n$sample"
    }

    /** render_code_mode_sample: the description, then the tool's TypeScript declaration. Claude Code
     *  tools declare no output schema, so every result is `Promise<unknown>` as codex renders it. */
    private fun sample(tool: NestedTool, global: String): String {
        val input = tool.inputSchema?.let(CodeModeSchemaTypes::render) ?: "unknown"
        val declaration = "declare const tools: { $global(args: $input): Promise<unknown>; };"
        return "${tool.description}\n\nexec tool declaration:\n```ts\n$declaration\n```"
    }
}
