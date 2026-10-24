// NEW: finds where the client's copy of a quiet script's reasoning and preface starts and ends in its own items.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import java.util.Collections
import java.util.IdentityHashMap

/** Where a script saved before records kept their client start started in the client's items: where the client's
 *  copy of its reasoning and preface starts, looked for nearest the item its baseline anchor names. That anchor counts
 *  what GPT was sent and finds an item by its digest and how often it came, so it can name an item before or after the
 *  one the script ran after, and names none the client holds when the script ran right after another one's exec
 *  output: then the copy is looked for from the floor, where the script before it ends in the client's items. A copy
 *  that holds reasoning is that script's alone, so it is looked for across the whole history; prose alone only right
 *  at the anchor or the floor, past the reasoning the client replays there, or just before an anchor. A script the
 *  client holds no copy of goes last when it ran after the whole history (its reply was lost and the client sends
 *  that history again). Any other is left out: no exact start was kept for it, and the build that saved it left it
 *  out of what GPT was sent once the client went on without it. */
internal class CodeModeLegacyStart(
    private val codec: CodexCodeModeHistoryCodec,
    private val scripts: CodeModeScriptPlacements,
    input: List<JsonElement>,
    private val conversation: List<JsonElement>,
) {
    private val logical = codec.conversation(codec.projection.project(JsonArray(input))).body.logicalItems
    private val kept = Collections.newSetFromMap(IdentityHashMap<JsonElement, Boolean>()).apply { addAll(logical) }

    fun at(record: CodeModeRecord, replay: List<JsonElement>, floor: Int?): Int? {
        val after = codec.baselineBoundary(logical, record)?.takeIf { it > 0 }?.let { boundary ->
            conversation.indexOfFirst { it === logical[boundary - 1] }.takeIf { it >= 0 }?.plus(1)
        }
        if (replay.isEmpty()) return after
        val near = after ?: floor ?: conversation.size
        val starts = if (scripts.holdsReasoning(replay)) nearest(near) else prose(after, floor)
        return starts.filter { it >= (floor ?: 0) }.firstOrNull { scripts.echo(conversation, it, replay) != null }
            ?: near.takeIf { after != null && it == conversation.size }
    }

    /** Where a copy of prose alone may start: at the anchor and past the reasoning replayed there, then the same at
     *  the floor, then just before the anchor; at the end when there is neither. An anchor that names a later item
     *  than the script ran after names one past each exec call and output GPT was sent ahead of it, so a script that
     *  ran right after another one starts at the floor, and the item before the anchor may be the next one's copy. */
    private fun prose(after: Int?, floor: Int?): Sequence<Int> {
        if (after == null && floor == null) return past(conversation.size)
        val before = after?.minus(1)?.takeIf { it >= 0 }
        return after?.let(::past).orEmpty() + floor?.let(::past).orEmpty() + listOfNotNull(before)
    }

    /** Every start in the history, nearest [near] first, the later of two as near first. */
    private fun nearest(near: Int): Sequence<Int> = sequence {
        for (distance in 0..conversation.size) {
            if (near + distance <= conversation.size) yield(near + distance)
            if (distance > 0 && near - distance >= 0) yield(near - distance)
        }
    }

    /** [near] and the starts past the reasoning the client replays there. */
    private fun past(near: Int): Sequence<Int> =
        (near..conversation.size).asSequence().takeWhile { it == near || conversation[it - 1] !in kept }
}

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

    fun from(items: List<JsonElement>, at: Int): CodeModeEcho? {
        var end = at
        while (reasoned < given.size || spoke < said.length) {
            val own = scripts.text(items.getOrNull(end))
            if (!consumed(items, end, own)) return CodeModeEcho(end, said.length - spoke).takeIf { endsIn(own) }
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
internal data class CodeModeEcho(val end: Int, val split: Int)
