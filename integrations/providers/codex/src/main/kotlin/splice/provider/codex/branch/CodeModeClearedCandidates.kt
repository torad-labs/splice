package splice.provider.codex.branch

import splice.core.parse.AnthropicTurnBody
import splice.core.wire.TextBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.upstream.codemode.CodeModeCall

/** Typed shape evidence only. A marker never authorizes replay without retained branch evidence. */
internal object CodeModeClearedCandidates {
    fun read(body: AnthropicTurnBody): Map<String, CodeModeCall> {
        val blocks = body.typed.messages.flatMap { it.content }
        val calls = blocks.mapIndexedNotNull { index, block ->
            (block as? ToolUseBlock)?.let { it.id to (index to it) }
        }.groupBy({ it.first }, { it.second })
        val positions = blocks.mapIndexedNotNull { index, block ->
            (block as? ToolResultBlock)?.let { it.toolUseId to index }
        }.toMap()
        return blocks.filterIsInstance<ToolResultBlock>().groupBy { it.toolUseId }.mapNotNull { (id, results) ->
            val result = results.singleOrNull()
            val call = calls[id]?.singleOrNull()
            if (result == null || call == null) return@mapNotNull null
            if (!soleMarker(result) || call.first >= positions.getValue(id)) return@mapNotNull null
            val use = call.second
            id to CodeModeCall(id, use.name, use.input)
        }.toMap()
    }

    private fun soleMarker(result: ToolResultBlock): Boolean =
        (result.content.singleOrNull() as? TextBlock)?.text == "[Old tool result content cleared]"
}
