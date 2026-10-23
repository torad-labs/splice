// NEW: folds each finished script's client calls into the exec call and output GPT was sent, as Codex records them.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CODE_MODE_FIELD_CALL_ID
import splice.provider.codex.CODE_MODE_FIELD_ROLE
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec

/**
 * Where a finished script that made no client call goes, since nothing of it is in the client's history but the
 * reasoning and preface it replays: after that replay when the client has it, else after the post the script
 * started on, or, once splice no longer holds that post, after the history the script was measured on when that is
 * the whole history (a request retried after its reply was lost, a restart).
 */
internal class CodeModeScriptPlacements(private val codec: CodexCodeModeHistoryCodec) {
    fun finished(record: CodeModeRecord): Boolean =
        record.phase == CodeModePhase.COMPLETED && record.progress.output != null

    /** Each script that made no client call and is not in [sent] yet, with the replay that places it. */
    fun silent(
        records: List<CodeModeRecord>,
        sent: List<JsonElement>,
    ): List<Pair<CodeModeRecord, List<JsonElement>>> {
        val placed = sent.mapNotNullTo(HashSet(), codec::callId)
        return records.filter { finished(it) && !it.abandoned() && it.clientIds().isEmpty() }
            .filterNot { it.origin.outerCallId in placed }
            .map { it to continuity(it) }
            .filter { (_, replay) -> replay.isNotEmpty() }
    }

    /** The scripts to send after [folded], in the order they ran: each [due] on the history before it. */
    fun appended(
        records: List<CodeModeRecord>,
        folded: List<JsonElement>,
        conversation: List<JsonElement>,
        due: CodeModeScriptDue,
    ): List<CodeModeRecord> {
        var prior = folded
        return records.filter { record ->
            val sends = unsent(record, prior, conversation) && due.after(record, prior)
            if (sends) prior = prior + continuity(record) + placement(record)
            sends
        }
    }

    /** Whether [record]'s script was measured on exactly [input], its preamble included. */
    fun startedOn(record: CodeModeRecord, input: List<JsonElement>): Boolean {
        val logical = codec.conversation(codec.projection.project(JsonArray(input))).body.logicalItems
        return codec.baselineBoundary(logical, record) == logical.size
    }

    /** The reasoning and preface GPT gave before the script's call, in the order it gave them. */
    fun continuity(record: CodeModeRecord): List<JsonElement> = codec.projection.rebuild(
        ResponsesCodeModeInput(
            record.carry.continuity,
            record.carry.replay.map { ResponsesCodeModeReplay(it.logicalOffset, null, it.items) },
        ),
    )

    fun placement(record: CodeModeRecord): List<JsonElement> =
        listOf(CodeModeCallReplay.item(record), codec.customOutput(record)) + record.accepted.durableMedia()

    /** The echo of [continuity] starting at [at] in [items], or null when the items there are not it. Reasoning and
     *  calls come back as they were given; prose comes back as text, and the client may join a script's preface and
     *  the prose GPT gave after it into one assistant message, so a preface is found at the start of what is left of
     *  the client's message, and an echo may end inside one. */
    fun echo(items: List<JsonElement>, at: Int, continuity: List<JsonElement>): CodeModeEcho? {
        var end = at
        var split = 0
        for (piece in continuity) {
            val given = text(piece)
            if (given == null) {
                if (split != 0 || !codec.continuityAt(items, end, listOf(piece))) return null
                end++
                continue
            }
            val own = text(items.getOrNull(end))
            if (own == null || !own.startsWith(given, split)) return null
            split += given.length
            if (split == own.length) {
                end++
                split = 0
            }
        }
        return CodeModeEcho(end, split)
    }

    /** [items] with the message [echo] ends inside cut in two where it ends, each part keeping the message's other
     *  fields: the script's preface, then what GPT said after it. */
    fun cut(items: List<JsonElement>, echo: CodeModeEcho): List<JsonElement> {
        if (echo.split == 0) return items
        val message = items[echo.end] as JsonObject
        val own = checkNotNull(text(message))
        val head = JsonObject(message + (ECHO_TEXT to JsonPrimitive(own.take(echo.split))))
        val rest = JsonObject(message + (ECHO_TEXT to JsonPrimitive(own.drop(echo.split))))
        return items.subList(0, echo.end) + head + rest + items.subList(echo.end + 1, items.size)
    }

    /** What follows [echo] in [items], the rest of the message it ends inside first. */
    fun after(items: List<JsonElement>, echo: CodeModeEcho): List<JsonElement> {
        val cut = cut(items, echo)
        return cut.subList(if (echo.split == 0) echo.end else echo.end + 1, cut.size)
    }

    /** Whether a script that made no client call is still to be sent: nothing of it is in the client's history. */
    private fun unsent(record: CodeModeRecord, prior: List<JsonElement>, conversation: List<JsonElement>): Boolean {
        val ids = record.clientIds()
        val called = conversation.any { codec.callId(it) in ids }
        val placed = prior.any { codec.callId(it) == record.origin.outerCallId }
        return finished(record) && !called && !placed
    }

    /** An assistant message's text, or null for any other item. */
    private fun text(item: JsonElement?): String? {
        val message = item as? JsonObject ?: return null
        if (codec.string(message, CODE_MODE_FIELD_ROLE) != ECHO_ROLE) return null
        return (message[ECHO_TEXT] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** Where a script's reasoning and preface end in the client's items: [end] items are wholly the echo's, and the
 *  first [split] characters of the next one, an assistant message the client joined with what GPT said after. */
internal data class CodeModeEcho(val end: Int, val split: Int)

/** Whether a script that made no client call is sent right after [prior], the history GPT is sent ahead of it. */
internal fun interface CodeModeScriptDue {
    fun after(record: CodeModeRecord, prior: List<JsonElement>): Boolean
}

/** One pass over the client's new items, folding each finished script's calls into its exec call and output, and
 *  sending each script that made no client call right after the reasoning and preface the client replays for it. */
internal class CodeModeScriptFold(
    private val codec: CodexCodeModeHistoryCodec,
    private val owners: Map<String, CodeModeRecord>,
    private val echoed: List<Pair<CodeModeRecord, List<JsonElement>>>,
    private val scripts: CodeModeScriptPlacements,
) {
    private val out = mutableListOf<JsonElement>()
    private val between = mutableListOf<JsonElement>()
    private val opened = mutableSetOf<String>()
    private val followUps = mutableSetOf<Int>()
    private val placements = HashMap<Int, MutableList<CodeModeRecord>>()
    private var open: CodeModeRecord? = null

    fun fold(client: List<JsonElement>): List<JsonElement> {
        val items = placed(client)
        val last = HashMap<String, Int>()
        items.forEachIndexed { index, item -> owner(item)?.let { last[it.id] = index } }
        items.forEachIndexed { index, item ->
            val record = owner(item)
            when {
                index in followUps -> Unit
                record != null -> callback(items, index, record, last[record.id] == index)
                else -> plain(items, index)
            }
        }
        return out + between
    }

    /** [items] with each echoed script's echo found, the message it ends inside cut where it ends, and the script
     *  set to go right after the echo's last item. */
    private fun placed(items: List<JsonElement>): List<JsonElement> {
        var cut = items
        echoed.forEach { (record, replay) ->
            val echo = cut.indices.firstNotNullOfOrNull { scripts.echo(cut, it, replay) } ?: return@forEach
            if (echo.split != 0) {
                cut = scripts.cut(cut, echo)
                val moved = placements.keys.filter { it >= echo.end }.sortedDescending()
                moved.forEach { placements[it + 1] = checkNotNull(placements.remove(it)) }
            }
            val lastItem = if (echo.split == 0) echo.end - 1 else echo.end
            placements.getOrPut(lastItem) { mutableListOf() } += record
        }
        return cut
    }

    private fun plain(items: List<JsonElement>, index: Int) {
        val into = if (open != null) between else out
        into += items[index]
        placements[index]?.forEach { into += scripts.placement(it) }
    }

    private fun callback(items: List<JsonElement>, index: Int, record: CodeModeRecord, closes: Boolean) {
        if (opened.add(record.id)) {
            out += CodeModeCallReplay.item(record)
            open = record
        }
        val result = codec.string(items[index] as? JsonObject, CODE_MODE_FIELD_TYPE) == TYPE_RESULT
        val media = codec.callId(items[index])?.takeIf { result }?.let(record.accepted::media).orEmpty()
        val end = minOf(index + 1 + media.size, items.size)
        if (media.isNotEmpty() && items.subList(index + 1, end) == media) followUps += (index + 1 until end)
        if (closes) {
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
private const val ECHO_ROLE = "assistant"
private const val ECHO_TEXT = "content"
