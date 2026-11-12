// NEW: finds where the client's copy of a quiet script's reasoning and preface starts and ends in its own items.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import splice.core.perf.InputItemDigests
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.placement.CodeModeLegacyStart
/** Where each finished script that made no client call ran in the client's [conversation], in the order they ran.
 *  One whose record keeps its client start ran right after that prefix. One an older build saved kept none, so it
 *  goes between the scripts around it: not before where the one ahead of it ends in the client's items (its floor),
 *  and not after where the next one whose place is known starts (its ceiling). [input] is the whole request, and
 *  [records] every script of the conversation, in the order they ran. */
internal class CodeModeQuietMeasure(
    private val codec: CodexCodeModeHistoryCodec,
    private val scripts: CodeModeScriptPlacements,
    input: List<JsonElement>,
    private val conversation: List<JsonElement>,
    digests: InputItemDigests,
    private val records: List<CodeModeRecord>,
) {
    private val prefix = CodeModeClientPrefix(conversation, digests)
    private val legacy by lazy { CodeModeLegacyStart(codec, scripts, input, conversation, records) }

    /** Where each record starts in [conversation] when that is known without its neighbours, else null. */
    private val starts = HashMap<Int, Int?>()

    fun measure(): List<CodeModeQuiet> {
        // Only a record an older build saved reads a floor, so none is measured past the last of them.
        val floored = records.indexOfLast { scripts.quiet(it) && it.replayAnchors?.client == null }
        var floor: CodeModeFloor? = null
        return records.mapIndexedNotNull { index, record ->
            val placed = if (scripts.quiet(record)) placed(records, index, floor, index < floored) else null
            if (index < floored) floor = placed?.floor ?: called(record)?.let(::CodeModeFloor) ?: floor
            placed?.let { CodeModeQuiet(record, CodeModeScriptDelivery.items(record, scripts), it.at) }
        }.sortedBy(CodeModeQuiet::at)
    }

    private fun placed(
        records: List<CodeModeRecord>,
        index: Int,
        floor: CodeModeFloor?,
        floors: Boolean,
    ): CodeModePlaced? {
        val record = records[index]
        val start = record.replayAnchors?.client
            ?: return legacy.at(record, scripts.continuity(record), floor, ceiling(records, index))
        return prefix.at(start)?.let { at -> started(record, at, floors) }
    }

    /** The place of [record], whose client start is at [at]. When a record an older build saved comes after it
     *  ([floors]), that one starts no earlier than where this one's copy ends, so the floor it leaves is there. */
    private fun started(record: CodeModeRecord, at: Int, floors: Boolean): CodeModePlaced {
        val copy = if (floors) scripts.echo(conversation, at, scripts.continuity(record)) else null
        return copy?.let { legacy.placed(at, it) } ?: CodeModePlaced(at, CodeModeFloor(at))
    }

    /** Where the first record after [index] whose place is known starts in the client's items, if any is. */
    private fun ceiling(records: List<CodeModeRecord>, index: Int): Int? =
        (index + 1 until records.size).firstNotNullOfOrNull { at ->
            if (at in starts) starts[at] else start(records[at]).also { starts[at] = it }
        }

    /** Where [record] starts in the client's items when that is known without its neighbours: a script that made
     *  client calls at its copy of reasoning and preface, which ends at its first call; one with a client start at
     *  that prefix; one an older build saved at its copy only when that holds reasoning, which is its alone. */
    private fun start(record: CodeModeRecord): Int? {
        val replay = scripts.continuity(record)
        val start = record.replayAnchors?.client
        return when {
            !scripts.quiet(record) -> first(record)?.let { copied(it, replay) }
            start != null -> prefix.at(start)
            legacy.holdsReasoning(replay) -> legacy.at(record, replay, null, null)?.at
            else -> null
        }
    }

    /** Where the copy that ends at [first] starts: the first start up to it whose copy of [replay] ends right there,
     *  else, when the client holds only part of it (it was never shown the reasoning), the first of the items right
     *  before [first] that are wholly the script's. */
    private fun copied(first: Int, replay: List<JsonElement>): Int {
        val earliest = (first - replay.size).coerceAtLeast(0)
        return (earliest..first).firstOrNull { at ->
            scripts.echo(conversation, at, replay)?.let { it.end == first && it.split == 0 } == true
        } ?: (first downTo earliest + 1).firstOrNull { at -> !owns(replay, conversation[at - 1]) } ?: earliest
    }

    /** Whether [item] is wholly [replay]'s: one of its reasoning items, or prose it said. */
    private fun owns(replay: List<JsonElement>, item: JsonElement): Boolean {
        val id = scripts.reasoning(item)
        val text = scripts.text(item)?.takeIf { it.isNotEmpty() }
        return when {
            id != null -> replay.any { scripts.reasoning(it) == id }
            text != null -> text in replay.mapNotNull(scripts::text).joinToString("")
            else -> false
        }
    }

    private fun first(record: CodeModeRecord): Int? {
        val ids = record.clientIds()
        return conversation.indexOfFirst { codec.callId(it) in ids }.takeIf { ids.isNotEmpty() && it >= 0 }
    }

    /** Where [record]'s last client item ends in [conversation], or null when the client holds none of them. */
    private fun called(record: CodeModeRecord): Int? {
        val ids = record.clientIds()
        return conversation.indexOfLast { codec.callId(it) in ids }.takeIf { ids.isNotEmpty() && it >= 0 }?.plus(1)
    }
}

/** Where a script goes, [at], and the [floor] it leaves for the next one. */
internal data class CodeModePlaced(val at: Int, val floor: CodeModeFloor?)

/** Where the items a script left in the client's history end: a later script starts at [next] or after, or, when the
 *  client joined its preface with what GPT said after, inside that message ([joined]). */
internal data class CodeModeFloor(val next: Int, val joined: CodeModeJoin? = null)

/** A message whose first [skip] characters are a script's preface, joined with what GPT said after: a script whose
 *  copy starts in the rest of [item] goes with the scripts placed at [at]. */
internal data class CodeModeJoin(val item: Int, val skip: Int, val at: Int)

/** One match of a script's [continuity] against the client's items from one start: the reasoning and calls as they
 *  were given and the prose as text, each in GPT's order, merged in whatever order GPT streamed them. */
internal class CodeModeEchoMatch(
    private val codec: CodexCodeModeHistoryCodec,
    private val scripts: CodeModeScriptPlacements,
    continuity: List<JsonElement>,
) {
    private val given = continuity.filter { scripts.text(it) == null }
    private val said = continuity.mapNotNull(scripts::text).joinToString("")
    private var reasoned = 0
    private var spoke = 0

    /** The echo from item [at], its first [skip] characters left out when it starts inside a message. */
    fun from(items: List<JsonElement>, at: Int, skip: Int): CodeModeEcho? {
        var end = at
        while (reasoned < given.size || spoke < said.length) {
            val text = scripts.text(items.getOrNull(end))
            val own = if (end == at && skip > 0) text?.drop(skip) ?: return null else text
            if (!consumed(items, end, own)) {
                val split = said.length - spoke + if (end == at) skip else 0
                return CodeModeEcho(end, split).takeIf { endsIn(own) }
            }
            end++
        }
        return CodeModeEcho(end, 0)
    }

    /** Whether the item at [at], whose text is [own], is the next reasoning or the next whole piece of prose. */
    private fun consumed(items: List<JsonElement>, at: Int, own: String?): Boolean = when {
        own == null -> (reasoned < given.size && codec.continuityAt(items, at, listOf(given[reasoned])))
            .also { if (it) reasoned++ }
        own.isNotEmpty() && said.startsWith(own, spoke) -> true.also { spoke += own.length }
        else -> false
    }

    /** Whether the echo ends inside the client's message [own]: all the reasoning met, and the rest of the prose
     *  starts it, joined with what GPT said after. */
    private fun endsIn(own: String?): Boolean = reasoned == given.size && own?.startsWith(said.substring(spoke)) == true
}

/** Where a script's reasoning and preface end in the client's items: [end] items are wholly the echo's, and the
 *  first [split] characters of the next one, an assistant message the client joined with what GPT said after. */
internal data class CodeModeEcho(val end: Int, val split: Int) {
    /** The last item that holds any of the echo. */
    val last: Int get() = if (split == 0) end - 1 else end
}
