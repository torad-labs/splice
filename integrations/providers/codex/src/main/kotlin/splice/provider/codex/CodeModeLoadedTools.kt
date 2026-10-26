// NEW (Oct 25, 2026, splice-lead): the client tools a code-mode cell's search loaded in the middle of a conversation.
// exec's manual leaves them out, as the turns before the load did, so the request's first item keeps its bytes and the
// provider's prompt cache keeps the conversation: at 2:05 CT on Oct 25 loading Monitor grew the manual from 42251 to
// 49555 characters, and the turn read 0 cached tokens right after a 99% hit. The search's own result describes them
// instead, which the conversation only ever appends to.
package splice.provider.codex

import splice.core.parse.AnthropicTurnBody
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolResultBlock
import splice.core.wire.UnknownBlock
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeToolDefinitions

/** The tools [body]'s client sends, as code mode describes them, and the ones a cell's search loaded. */
internal class CodeModeLoadedTools(private val body: AnthropicTurnBody) {
    /** V4-388: [tool]'s `ALL_TOOLS` description as codex's augment_tool_definition writes it, with its TypeScript
     *  declaration, so a tool the manual defers still shows the cell its arguments. */
    fun described(tool: ToolDefinition): String = CodeModeManual.augmented(
        CodeModeManual.NestedTool(
            tool.name,
            tool.description.orEmpty(),
            CodeModeToolDefinitions.parameters(tool.inputSchema ?: CodeModeToolDefinitions.emptyInputSchema),
        ),
    )

    /** The catalog tools a cell's search loaded: each `tool_reference` in [results] whose tool the client now sends.
     *  These are exactly the ones [text] describes, so no tool leaves exec's manual unless the model reads it in the
     *  search's result. A tool that was already in the manual and is loaded again leaves it once. */
    fun names(results: List<ToolResultBlock>): Set<String> {
        val catalog = body.typed.tools.map { it.name }.toSet()
        return results.flatMap { it.content }
            .mapNotNull { (it as? UnknownBlock)?.loadedTool }
            .filterTo(mutableSetOf()) { it in catalog }
    }

    /** What a cell reads for [part] when it names a tool the client just loaded: how a cell calls it, then its
     *  description and declaration as `ALL_TOOLS` carries them. Null for any other block, and for a tool the client's
     *  catalog does not hold, which keeps the omission marker. */
    fun text(part: UnknownBlock): String? {
        val name = part.loadedTool ?: return null
        val tool = body.typed.tools.firstOrNull { it.name == name } ?: return null
        val call = "tools.${CodeModeManual.identifier(name)}"
        return "[tool loaded: $name. A cell calls it as $call.]\n${described(tool)}\n"
    }
}
