// NEW: folds each finished script's client calls into the exec call and output GPT was sent, as Codex records them.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.provider.codex.CODE_MODE_FIELD_CALL_ID
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec

/** One pass over the client's new items, folding each finished script's calls into its exec call and output, and
 *  sending each [quiet] script right after the history it ran on, its reasoning and preface as GPT gave them in place
 *  of the copy the client replays there: the bytes GPT was sent when the script finished, so a history folded again
 *  (a restart, a reanchor) starts with what GPT was last sent. */
internal class CodeModeScriptFold(
    private val codec: CodexCodeModeHistoryCodec,
    private val owners: Map<String, CodeModeRecord>,
    private val quiet: List<CodeModeQuiet>,
    private val scripts: CodeModeScriptPlacements,
) {
    private val reasoning = CodeModeReasoningRestore(codec, scripts)
    private val delivered = CodeModeDeliveredEchoes(codec, scripts)
    private val out = mutableListOf<JsonElement>()
    private val between = mutableListOf<JsonElement>()
    private val opened = mutableSetOf<String>()
    private val followUps = mutableSetOf<Int>()
    private val echoed = mutableSetOf<Int>()
    private val placements = HashMap<Int, MutableList<CodeModeRecord>>()
    private var open: CodeModeRecord? = null

    fun fold(client: List<JsonElement>): List<JsonElement> {
        val items = placed(delivered.withoutAfterExec(client, owners.values.distinct()))
        placements[-1]?.forEach { out += scripts.continuity(it) + scripts.placement(it) }
        val last = HashMap<String, Int>()
        items.forEachIndexed { index, item -> owner(item)?.let { last[it.id] = index } }
        items.forEachIndexed { index, item ->
            val record = owner(item)
            when {
                index in followUps || index in echoed -> Unit
                record != null -> callback(items, index, record, last[record.id] == index)
                else -> into() += item
            }
            placements[index]?.forEach { into() += scripts.continuity(it) + scripts.placement(it) }
        }
        return out + between
    }

    /** [items] with each quiet script set to go right after the history it ran on: after its echo when that starts
     *  there or past the copies other scripts streamed ahead of it, the message the echo ends inside cut where it
     *  ends, or right there for a script that gave no reasoning or preface, or whose reply the client never kept (a
     *  restart cut it) before it went on. Scripts that ran on one history ran in turn, each echo after the one before;
     *  [quiet] is in the order of where they ran. A script whose echo is not there and that ran after all of [items]
     *  is left to [CodeModeScriptPlacements.appended], which sends it last, since the client may still replay it. */
    private fun placed(items: List<JsonElement>): List<JsonElement> {
        var cut = items
        var start = -1
        var from = 0
        var added = 0
        quiet.forEachIndexed { index, script ->
            if (script.at != start) {
                start = script.at
                from = script.at + added
            }
            val copy = scripts.copyStart(cut, from, script.replay) { item -> another(index, item) }
            if (copy == null) {
                if (from < cut.size) placements.getOrPut(from - 1) { mutableListOf() } += script.record
                return@forEachIndexed
            }
            val echo = copy.echo
            if (echo.split != 0) {
                cut = split(cut, echo)
                added++
            }
            echoed += copy.start..echo.last
            placements.getOrPut(echo.last) { mutableListOf() } += script.record
            from = echo.last + 1
        }
        return cut
    }

    /** The reasoning and preface of each script whose copy the client may hold: the [quiet] ones in their order, then
     *  those that made client calls, whose copies end at their first call. */
    private val copies: List<List<JsonElement>> by lazy {
        quiet.map(CodeModeQuiet::replay) + owners.values.distinct().map(scripts::continuity)
    }

    /** Whether [item] is in the copy of a script other than the quiet one at [index]: its reasoning, or a message
     *  that opens with the prose it said or that its prose opens with. */
    private fun another(index: Int, item: JsonElement): Boolean {
        val id = scripts.reasoning(item)
        val text = scripts.text(item)?.takeIf { it.isNotEmpty() }
        return copies.indices.any { other ->
            other != index && copies[other].let { replay ->
                if (id != null) {
                    replay.any { scripts.reasoning(it) == id }
                } else {
                    val said = replay.mapNotNull(scripts::text).joinToString("")
                    text != null && said.isNotEmpty() && (text.startsWith(said) || said.startsWith(text))
                }
            }
        }
    }

    /** [items] with the message [echo] ends inside cut in two, and every script placed from there on moved one on. */
    private fun split(items: List<JsonElement>, echo: CodeModeEcho): List<JsonElement> {
        val moved = placements.keys.filter { it >= echo.end }.sortedDescending()
        moved.forEach { placements[it + 1] = checkNotNull(placements.remove(it)) }
        return scripts.cut(items, echo)
    }

    /** Where an item goes: after the open script's output when one is open, else straight out. */
    private fun into(): MutableList<JsonElement> = if (open != null) between else out

    private fun callback(items: List<JsonElement>, index: Int, record: CodeModeRecord, closes: Boolean) {
        if (opened.add(record.id)) {
            reasoning.restore(out, record)
            out += CodeModeCallReplay.item(record)
            out += record.carry.nativeAfterExec.orEmpty()
            open = record
        }
        val result = codec.string(items[index] as? JsonObject, CODE_MODE_FIELD_TYPE) == TYPE_RESULT
        val media = codec.callId(items[index])?.takeIf { result }?.let(record.accepted::media).orEmpty()
        val end = minOf(index + 1 + media.size, items.size)
        if (media.isNotEmpty() && items.subList(index + 1, end) == media) followUps += (index + 1 until end)
        if (closes) {
            out += record.progress.nativeNotifications
            out += codec.customOutput(record)
            out += record.accepted.durableMedia()
            out += between
            between.clear()
            open = null
        }
    }

    private fun owner(item: JsonElement): CodeModeRecord? {
        val type = codec.string(item as? JsonObject, CODE_MODE_FIELD_TYPE)
        if (type != TYPE_CALL && type != TYPE_RESULT) return null
        return owners[codec.string(item as? JsonObject, CODE_MODE_FIELD_CALL_ID)]
    }
}

private const val TYPE_CALL = "function_call"
private const val TYPE_RESULT = "function_call_output"
