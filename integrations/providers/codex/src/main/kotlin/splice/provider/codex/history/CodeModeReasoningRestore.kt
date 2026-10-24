// NEW: restores native source continuity at the persisted preface boundaries of an owned callback.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.state.CodeModeNativeChain

internal class CodeModeReasoningRestore(
    private val codec: CodexCodeModeHistoryCodec,
    private val scripts: CodeModeScriptPlacements,
) {
    /** An owned callback retains its native source preface and reasoning even when the client omits their echo. */
    fun restore(out: MutableList<JsonElement>, record: CodeModeRecord) {
        if (record.carry.replay.isEmpty() && record.carry.continuity.isEmpty()) return
        val projected = codec.projection.project(JsonArray(out))
        val start = continuityStart(projected.logicalItems, record)
        val slots = CodeModePrefaceSlots(projected, record, start, scripts)
        val replay = record.carry.replay.map { segment ->
            ResponsesCodeModeReplay(slots.position(segment.logicalOffset), null, segment.items)
        }
        val rebuilt = codec.projection.rebuild(
            ResponsesCodeModeInput(slots.logical, CodeModeNativeChain.emittedReplay(slots.replay + replay)),
        )
        out.clear()
        out.addAll(rebuilt)
    }

    /** The owned callback bounds this prose suffix; the existing matcher handles joined and coalesced messages. */
    private fun continuityStart(items: List<JsonElement>, record: CodeModeRecord): Int {
        if (record.carry.continuity.isEmpty()) return items.size
        val floor = items.indexOfLast { scripts.text(it) == null } + 1
        return (floor until items.size).firstOrNull { at ->
            scripts.echo(items, at, record.carry.continuity) != null
        } ?: items.size
    }
}

/** Source item boundaries mapped onto the client's exact text echo, splitting coalesced messages where needed. */
private class CodeModePrefaceSlots(
    input: ResponsesCodeModeInput,
    record: CodeModeRecord,
    private val start: Int,
    private val scripts: CodeModeScriptPlacements,
) {
    var logical = input.logicalItems
        private set
    var replay = input.replayItems
        private set
    private val slots = mutableMapOf(0 to start)

    init {
        val owned = record.carry.replay.flatMap { it.items }.toSet()
        replay = replay.mapNotNull { segment ->
            val items = if (segment.logicalOffset < start) {
                segment.items
            } else {
                segment.items.filterNot(owned::contains)
            }
            segment.copy(items = items).takeIf { it.items.isNotEmpty() }
        }
        if (start == logical.size) logical = logical + record.carry.continuity
        record.carry.continuity.indices.forEach { index ->
            split(record.carry.continuity.take(index + 1), index + 1)
        }
    }

    fun position(offset: Int): Int = slots[offset] ?: logical.size

    private fun split(prefix: List<JsonElement>, offset: Int) {
        val echo = checkNotNull(scripts.echo(logical, start, prefix)) { "source preface echo changed during replay" }
        if (echo.split > 0) {
            logical = scripts.cut(logical, echo)
            replay = replay.map { segment ->
                if (segment.logicalOffset > echo.end) {
                    segment.copy(logicalOffset = segment.logicalOffset + 1)
                } else {
                    segment
                }
            }
        }
        slots[offset] = echo.end + if (echo.split > 0) 1 else 0
    }
}
