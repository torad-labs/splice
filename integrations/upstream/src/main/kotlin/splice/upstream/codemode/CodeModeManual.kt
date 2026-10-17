// NEW: V4-388 — codex-rs code-mode-protocol/src/description.rs and core/src/tools/code_mode/execute_spec.rs,
// ported: the one `exec` tool a code_mode_only model is trained on, whose description is the manual of
// every nested tool. The template (exec-description.txt) is codex's EXEC_DESCRIPTION_TEMPLATE with the
// lines splice's runtime does not honor removed — image, audio, generatedImage, store, load, notify,
// setTimeout, clearTimeout, yield_control, and the `// @exec:` pragma with its yield_time_ms and
// max_output_tokens (no wait tool, nothing parses them) — and the argument/return lines stated as the
// cell runs them (an object in, the output string back; Bash as the example, exec_command is codex's).
// codex swaps helper lines by runtime capability the same way (ImageDetailVisibility).
package splice.upstream.codemode

import kotlinx.serialization.json.JsonElement

public object CodeModeManual {
    /** codex-rs code-mode-protocol PUBLIC_TOOL_NAME. */
    public const val TOOL_NAME: String = "exec"

    /** Producer commitment for native intrinsics that may execute before the source finishes. */
    public val streamingSealedGlobals: Set<String> = setOf("String", "Number", "Boolean", "Promise")

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
        val sealed = streamingSealedGlobals.sorted().joinToString(", ") { "`$it`" }
        val sections = mutableListOf(
            template,
            "Streaming fixes the native $sealed bindings. Do not redeclare these names at script scope. " +
                "Awaited Promise.all or Promise.allSettled arrays of direct tools calls can run before the " +
                "remaining source arrives. Other operations may wait for the complete source before execution.",
        )
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
