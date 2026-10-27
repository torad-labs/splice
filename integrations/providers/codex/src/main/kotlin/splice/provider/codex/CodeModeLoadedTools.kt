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
import splice.dialect.responses.request.ResponsesDynamicToolChanges
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeToolDefinitions
import java.util.IdentityHashMap

/** Current native catalog descriptions and the original schema at each historical source load. */
internal class CodeModeLoadedTools(
    body: AnthropicTurnBody,
    private val availability: ResponsesDynamicToolChanges.Availability =
        ResponsesDynamicToolChanges.availability(body.typed),
) {
    private val loaded: Map<UnknownBlock, ToolDefinition> = IdentityHashMap<UnknownBlock, ToolDefinition>().apply {
        availability.loadedDefinitions.forEach { put(it.reference, it.definition) }
    }

    /** V4-388: `ALL_TOOLS` descriptions include the declaration even for tools omitted from the manual. */
    fun descriptions(): Map<String, String> = availability.currentCatalog.associate { it.name to described(it) }

    /** V4-388: [tool]'s description and TypeScript declaration as the cell's `ALL_TOOLS` carries them. */
    fun described(tool: ToolDefinition): String = CodeModeManual.augmented(
        CodeModeManual.NestedTool(
            tool.name,
            tool.description.orEmpty(),
            CodeModeToolDefinitions.parameters(tool.inputSchema ?: CodeModeToolDefinitions.emptyInputSchema),
        ),
    )

    /** Only references with a real definition at their source boundary may leave the initial manual. */
    fun names(results: List<ToolResultBlock>): Set<String> = results.flatMap { it.content }
        .filterIsInstance<UnknownBlock>()
        .mapNotNull { loaded[it]?.name }
        .toSet()

    /** Repeated identical references retain their own schema instead of reading the latest revision. */
    fun text(part: UnknownBlock): String? {
        val tool = loaded[part] ?: return null
        val call = "tools.${CodeModeManual.identifier(tool.name)}"
        return "[tool loaded: ${tool.name}. A cell calls it as $call.]\n${described(tool)}\n"
    }
}
