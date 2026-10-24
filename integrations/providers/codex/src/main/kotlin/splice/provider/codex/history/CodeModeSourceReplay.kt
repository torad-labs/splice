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
        val present = replay.filter { it.logicalOffset >= start }.flatMap { it.items }.toMutableSet()
        items.forEachIndexed { index, item ->
            if (!present.add(item)) return@forEachIndexed
            val before = items.drop(index + 1).firstOrNull { holds(kept, offset, it) }
            val after = items.take(index).lastOrNull { holds(kept, offset, it) }
            kept = insert(kept, ResponsesCodeModeReplay(offset, null, listOf(item)), before, after)
        }
        return kept
    }

    private fun holds(replay: List<ResponsesCodeModeReplay>, offset: Int, item: JsonElement): Boolean =
        replay.any { it.logicalOffset == offset && item in it.items }

    private fun insert(
        replay: List<ResponsesCodeModeReplay>,
        missing: ResponsesCodeModeReplay,
        before: JsonElement?,
        after: JsonElement?,
    ): List<ResponsesCodeModeReplay> {
        val anchor = before ?: after ?: return replay + missing
        val index = replay.indexOfFirst { it.logicalOffset == missing.logicalOffset && anchor in it.items }
        if (index < 0) return replay + missing
        val segment = replay[index]
        val at = segment.items.indexOf(anchor) + if (before == null) 1 else 0
        val joined = segment.copy(items = segment.items.take(at) + missing.items + segment.items.drop(at))
        return replay.take(index) + joined + replay.drop(index + 1)
    }
}
