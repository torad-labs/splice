// NEW: fills missing source reasoning beside its captured neighbors without moving existing native items.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import splice.dialect.responses.request.ResponsesCodeModeReplay

internal object CodeModeSourceReplay {
    fun fill(
        replay: List<ResponsesCodeModeReplay>,
        offset: Int,
        items: List<JsonElement>,
        start: Int,
    ): List<ResponsesCodeModeReplay> {
        var kept = replay
        val present = replay.filter { it.logicalOffset >= start }.flatMap { it.items }
            .mapTo(mutableSetOf(), CodeModeReasoningIdentity::key)
        items.forEachIndexed { index, item ->
            if (!present.add(CodeModeReasoningIdentity.key(item))) {
                kept = coalesce(kept, start, item)
                return@forEachIndexed
            }
            val before = items.drop(index + 1).firstOrNull { holds(kept, offset, it) }
            val after = items.take(index).lastOrNull { holds(kept, offset, it) }
            kept = insert(kept, ResponsesCodeModeReplay(offset, null, listOf(item)), before, after)
        }
        return kept
    }

    fun preferred(replay: List<ResponsesCodeModeReplay>, start: Int, item: JsonElement): JsonElement =
        replay.asSequence().filter { it.logicalOffset >= start }.flatMap { it.items.asSequence() }
            .filter { CodeModeReasoningIdentity.same(item, it) }.fold(item, CodeModeReasoningIdentity::preferred)

    /** Keep the first verified position while upgrading its payload and removing copies owned by this source. */
    private fun coalesce(
        replay: List<ResponsesCodeModeReplay>,
        start: Int,
        item: JsonElement,
    ): List<ResponsesCodeModeReplay> {
        if (CodeModeReasoningIdentity.id(item) == null) return replay
        val selected = preferred(replay, start, item)
        var retained = false
        return replay.mapNotNull { segment ->
            if (segment.logicalOffset < start) return@mapNotNull segment
            val kept = segment.items.mapNotNull { candidate ->
                when {
                    !CodeModeReasoningIdentity.same(candidate, item) -> candidate
                    retained -> null
                    else -> selected.also { retained = true }
                }
            }
            segment.copy(items = kept).takeIf { it.items.isNotEmpty() }
        }
    }

    private fun holds(replay: List<ResponsesCodeModeReplay>, offset: Int, item: JsonElement): Boolean =
        replay.any { segment ->
            segment.logicalOffset == offset && segment.items.any { CodeModeReasoningIdentity.same(it, item) }
        }

    private fun insert(
        replay: List<ResponsesCodeModeReplay>,
        missing: ResponsesCodeModeReplay,
        before: JsonElement?,
        after: JsonElement?,
    ): List<ResponsesCodeModeReplay> {
        val anchor = before ?: after ?: return replay + missing
        val index = replay.indexOfFirst { holds(listOf(it), missing.logicalOffset, anchor) }
        if (index < 0) return replay + missing
        val segment = replay[index]
        val anchorAt = segment.items.indexOfFirst { CodeModeReasoningIdentity.same(it, anchor) }
        val at = anchorAt + if (before == null) 1 else 0
        val joined = segment.copy(items = segment.items.take(at) + missing.items + segment.items.drop(at))
        return replay.take(index) + joined + replay.drop(index + 1)
    }
}
