// NEW: owned source echoes use native identity without changing captured input validation.
package splice.provider.codex.state.replay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeReasoningIdentity
import splice.provider.codex.history.CodeModeReasoningRestore
import splice.provider.codex.history.CodeModeScriptPlacements
import splice.provider.codex.state.CodeModeCanonicalPlacement
import splice.provider.codex.state.CodeModeHistoryIndex

/** Source aliases are bounded by the baseline and first owned call; later client messages are independent. */
internal class CodeModeCanonicalSources(
    private val codec: CodexCodeModeHistoryCodec,
    private val input: ResponsesCodeModeInput,
    private val index: CodeModeHistoryIndex,
) {
    private val scripts = CodeModeScriptPlacements(codec)
    private val restore = CodeModeReasoningRestore(codec, scripts)
    private val copies = mutableMapOf<Int, MutableSet<JsonElement>>()

    fun prepare(placement: CodeModeCanonicalPlacement): CodeModeCanonicalPlacement {
        val record = placement.record
        if (record.carry.continuity.isEmpty() && record.carry.replay.isEmpty()) return placement
        val end = index.owned(record).firstOrNull()?.takeIf {
            codec.string(input.logicalItems[it] as? JsonObject, "type") in setOf("function_call", "custom_tool_call")
        } ?: placement.boundary
        val echo = echo(record, placement.boundary, end)
        val start = echo.firstOrNull() ?: placement.boundary
        val logical = echo.map(input.logicalItems::get)
        val received = received(record, placement.boundary..end).map {
            it.copy(logicalOffset = (it.logicalOffset - start).coerceIn(0, logical.size), callbackId = null)
        }
        val native = codec.projection.rebuild(ResponsesCodeModeInput(logical, received)).toMutableList()
        restore.restore(native, record)
        val source = codec.projection.project(JsonArray(native))
        return placement.copy(
            removed = placement.removed + echo,
            source = CodeModeContinuity(
                source.logicalItems,
                source.replayItems.map { CodeModeNativeSegment(it.logicalOffset, it.items) },
            ),
        )
    }

    fun owns(segment: ResponsesCodeModeReplay, item: JsonElement): Boolean =
        CodeModeReasoningIdentity.key(item) in copies[segment.logicalOffset].orEmpty()

    private fun received(record: CodeModeRecord, range: IntRange): List<ResponsesCodeModeReplay> {
        val owned = record.carry.replay.flatMap { it.items }.mapTo(mutableSetOf(), CodeModeReasoningIdentity::key)
        return input.replayItems.filter { it.logicalOffset in range }.mapNotNull { segment ->
            val items = segment.items.filter { CodeModeReasoningIdentity.key(it) in owned }
            val ids = items.filter { CodeModeReasoningIdentity.id(it) != null }
            copies.getOrPut(segment.logicalOffset) { mutableSetOf() }.addAll(ids.map(CodeModeReasoningIdentity::key))
            segment.copy(items = items).takeIf { it.items.isNotEmpty() }
        }
    }

    private fun echo(record: CodeModeRecord, lower: Int, end: Int): IntRange {
        if (record.carry.continuity.isEmpty()) return IntRange.EMPTY
        val start = (lower..end).firstOrNull { at ->
            val matched = scripts.echo(input.logicalItems, at, record.carry.continuity)
            matched?.let { it.end == end && it.split == 0 } == true
        } ?: return IntRange.EMPTY
        return start until end
    }
}
