package splice.provider.codex.history.placement

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.provider.codex.CODE_MODE_FIELD_ROLE
import splice.provider.codex.CODE_MODE_METADATA_VERSION
import splice.provider.codex.CODE_MODE_ROLE_USER
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeEcho
import splice.provider.codex.history.CodeModeFloor
import splice.provider.codex.history.CodeModeJoin
import splice.provider.codex.history.CodeModePlaced
import splice.provider.codex.history.CodeModeScriptPlacements
import splice.provider.codex.state.CodeModeHistoryIndex
import java.util.Collections
import java.util.IdentityHashMap
/** Where a script an older build saved started in the client's items, between its [CodeModeFloor] and its ceiling:
 *  where the client's copy of its reasoning and preface starts, looked for at the floor and then nearest the item
 *  its baseline anchor names. That anchor counts what GPT was sent and finds an item by its digest and how often it
 *  came, so it can name an item before or after the one the script ran after, or none the client holds. A copy that
 *  holds reasoning is that script's alone, so it is looked for anywhere between them; prose alone only at the floor,
 *  inside the message the script before it ends in, at the anchor, past the reasoning the client replays at either,
 *  or just before the anchor. A script that left the client nothing goes at its anchor, moved between them. One the
 *  client holds no copy of goes last when it ran after the whole history (its reply was lost and the client sends
 *  that history again). Any other is left out: no exact start was kept for it, and the build that saved it left it
 *  out of what GPT was sent once the client went on without it. */
internal class CodeModeLegacyStart(
    private val codec: CodexCodeModeHistoryCodec,
    private val scripts: CodeModeScriptPlacements,
    input: List<JsonElement>,
    private val conversation: List<JsonElement>,
    records: List<CodeModeRecord>,
) {
    private val logical = codec.conversation(codec.projection.project(JsonArray(input))).body.logicalItems
    private val kept = Collections.newSetFromMap(IdentityHashMap<JsonElement, Boolean>()).apply { addAll(logical) }
    private val index by lazy { CodeModeHistoryIndex(logical, codec) }

    /** The reasoning and prose of every script's copy, which the client may hold. */
    private val copies by lazy { records.flatMap(scripts::continuity) }
    private val reasoned by lazy { copies.mapNotNullTo(HashSet(), scripts::reasoning) }
    private val said by lazy { copies.mapNotNullTo(HashSet(), scripts::text) }

    fun at(record: CodeModeRecord, replay: List<JsonElement>, floor: CodeModeFloor?, ceiling: Int?): CodeModePlaced? {
        val after = after(record)
        val low = floor?.next ?: 0
        val high = ceiling?.coerceAtLeast(low) ?: conversation.size
        if (replay.isEmpty()) return bare(after, floor, high)
        // The next script starts at the ceiling, so a copy that starts there is its.
        return joined(replay, floor) ?: copied(replay, after, floor, low until high)
            ?: after?.takeIf { it == conversation.size && it == high }?.let { CodeModePlaced(it, CodeModeFloor(it)) }
    }

    /** Where the client's items the baseline anchor names end, or null when it names none the client holds. */
    private fun after(record: CodeModeRecord): Int? =
        codec.baselineBoundary(logical, record)?.let { counted -> held(record, counted) }?.takeIf { it > 0 }
            ?.let { boundary -> conversation.indexOfFirst { it === logical[boundary - 1] }.takeIf { it >= 0 }?.plus(1) }

    /** Where the anchor's count of [record]'s history ends among the client's items, given it ends at [counted] when
     *  every item it counts is there. The anchor names the last item GPT was sent that no script made, then counts
     *  the items scripts made after it: their copies, which the client may hold, and the exec calls and outputs splice
     *  made, which it never does. So the count stops at the first of the client's items that is no script's copy. */
    private fun held(record: CodeModeRecord, counted: Int): Int {
        val anchor = record.replayAnchors?.baseline?.takeIf { it.logicalTail > 0 }
            ?.takeIf { record.origin.baseline.metadataVersion == CODE_MODE_METADATA_VERSION }
        val prior = anchor?.let { index.resolve(it.copy(logicalTail = 0)) } ?: return counted
        return (prior until counted).firstOrNull { !copied(logical[it]) } ?: counted
    }

    /** Whether [item] is in some script's copy: its reasoning, or prose it said. */
    private fun copied(item: JsonElement): Boolean =
        scripts.reasoning(item)?.let { it in reasoned } ?: (scripts.text(item)?.let { it in said } == true)

    /** The copy of [replay] in the rest of the message the script before it ends inside, if it is there. */
    private fun joined(replay: List<JsonElement>, floor: CodeModeFloor?): CodeModePlaced? {
        val join = floor?.joined ?: return null
        return scripts.echo(conversation, join.item, replay, join.skip)?.let { placed(join.at, it) }
    }

    /** The first copy of [replay] that starts in [window], looked for in the order its kind allows. */
    private fun copied(
        replay: List<JsonElement>,
        after: Int?,
        floor: CodeModeFloor?,
        window: IntRange,
    ): CodeModePlaced? {
        val near = after ?: floor?.next ?: conversation.size
        val starts = if (holdsReasoning(replay)) nearest(near) else prose(after, floor)
        return starts.filter { it in window }.firstNotNullOfOrNull { start ->
            scripts.echo(conversation, start, replay)?.let { placed(start, it) }
        }
    }

    /** Whether [continuity] holds reasoning, which no other script's copy holds, and not only prose. */
    fun holdsReasoning(continuity: List<JsonElement>): Boolean = continuity.any { scripts.text(it) == null }

    /** A script that left the client nothing ran where its anchor says, moved between its floor and [high]. At the
     *  message the script before it ends inside, it ran right after that one, with which it then goes. */
    private fun bare(after: Int?, floor: CodeModeFloor?, high: Int): CodeModePlaced? {
        val join = floor?.joined
        val at = after?.coerceIn(minOf(join?.item ?: floor?.next ?: 0, high), high) ?: return null
        return if (join?.item == at) CodeModePlaced(join.at, floor) else CodeModePlaced(at, CodeModeFloor(at))
    }

    /** The place of a script whose copy [echo] ends, among the scripts placed at [at]. A copy that ends inside a
     *  message leaves the rest of it to the next script, which then goes with them. */
    fun placed(at: Int, echo: CodeModeEcho): CodeModePlaced {
        val joined = CodeModeJoin(echo.end, echo.split, at).takeIf { echo.split > 0 }
        return CodeModePlaced(at, CodeModeFloor(if (joined == null) echo.end else echo.end + 1, joined))
    }

    /** Where a copy of prose alone may start: at the floor and past the reasoning replayed there, then the same at
     *  the anchor, then just before the anchor; at the end when there is neither. The floor is where the script
     *  before it ends, so a copy found there is this script's and not a later one's with the same preface. A user
     *  message the client holds before the anchor came before the script, so none starts ahead of it: GPT's answer
     *  before that message may open with the same words. */
    private fun prose(after: Int?, floor: CodeModeFloor?): Sequence<Int> {
        if (after == null && floor == null) return past(conversation.size)
        val before = after?.minus(1)?.takeIf { it >= 0 }
        val asked = after?.let { anchor -> (anchor - 1 downTo 0).firstOrNull { asked(conversation[it]) }?.plus(1) }
        val floored = floor?.next?.let(::past).orEmpty().filter { it >= (asked ?: 0) }
        return floored + after?.let(::past).orEmpty() + listOfNotNull(before)
    }

    /** Whether [item] is a message the user wrote. */
    private fun asked(item: JsonElement): Boolean =
        codec.string(item as? JsonObject, CODE_MODE_FIELD_ROLE) == CODE_MODE_ROLE_USER

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
