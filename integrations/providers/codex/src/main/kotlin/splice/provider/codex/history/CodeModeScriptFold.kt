// NEW: folds each finished script's client calls into the exec call and output GPT was sent, as Codex records them.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.InputItemDigests
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CODE_MODE_FIELD_CALL_ID
import splice.provider.codex.CODE_MODE_FIELD_ROLE
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CODE_MODE_TYPE_REASONING
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec

/**
 * Where a finished script that made no client call goes, since nothing of it is in the client's history but the
 * reasoning and preface it replays: right after the history it ran on, which is where that replay starts when the
 * client has it. That history is the post the script started on while splice holds it, else the client's own items
 * the script started after, which its record keeps. A script whose replay the client does not have yet (a request
 * retried after its reply was lost) goes after the whole history once it was measured on all of it.
 */
internal class CodeModeScriptPlacements(private val codec: CodexCodeModeHistoryCodec) {
    fun finished(record: CodeModeRecord): Boolean =
        record.phase == CodeModePhase.COMPLETED && record.progress.output != null

    /** Each script that made no client call, started on [entry]'s post and is not in it, in the order they ran:
     *  the history each was measured on ends where the client's new items begin. */
    fun since(records: List<CodeModeRecord>, entry: CodeModeLedgerEntry): List<CodeModeQuiet> {
        val placed = entry.sent.mapNotNullTo(HashSet(), codec::callId)
        return records.filter { quiet(it) && it.postedOn == entry.id && it.origin.outerCallId !in placed }
            .map { CodeModeQuiet(it, continuity(it), 0) }
    }

    /** Each script that made no client call, in the order they ran, with where in [conversation] the history it
     *  ran on ends, while [conversation] still starts with that history. A script whose history the client cut or
     *  edited away ran on a branch GPT is no longer sent. [input] is the whole request, its preamble included. */
    fun measured(
        records: List<CodeModeRecord>,
        input: List<JsonElement>,
        conversation: List<JsonElement>,
        digests: InputItemDigests,
    ): List<CodeModeQuiet> = CodeModeQuietMeasure(codec, this, input, conversation, digests, records).measure()

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
     *  calls come back as they were given and prose as text, each in the order GPT gave it, but merged in the order
     *  GPT streamed them, which may put reasoning after the preface, while GPT is sent a script's reasoning first.
     *  The client may join a script's preface and the prose GPT gave after it into one assistant message, so a
     *  preface is found at the start of what is left of the client's message, and an echo may end inside one. */
    fun echo(items: List<JsonElement>, at: Int, continuity: List<JsonElement>, skip: Int = 0): CodeModeEcho? =
        CodeModeEchoMatch(codec, this, continuity).from(items, at, skip)

    /** The client's copy of [continuity] in [items] from [at]: there, or further on past the copies other scripts
     *  streamed in the same reply ahead of it (one whose record is gone or was lost): only past reasoning or prose
     *  GPT streamed, which the client keeps as it came, never past a message the client added, nor past one that
     *  [another] script's copy holds. Null when the client holds no copy there. */
    fun copyStart(
        items: List<JsonElement>,
        at: Int,
        continuity: List<JsonElement>,
        another: CodeModeOtherCopy,
    ): CodeModeCopy? = (at until items.size).asSequence()
        .takeWhile { start ->
            val item = items.getOrNull(start - 1)
            start == at || item != null && (text(item) != null || reasoning(item) != null) && !another.holds(item)
        }
        .firstNotNullOfOrNull { start -> echo(items, start, continuity)?.let { CodeModeCopy(start, it) } }

    /** [item]'s id when it is reasoning, else null. */
    fun reasoning(item: JsonElement?): String? {
        val given = item as? JsonObject ?: return null
        if (codec.string(given, CODE_MODE_FIELD_TYPE) != CODE_MODE_TYPE_REASONING) return null
        return codec.string(given, REASONING_ID)
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

    /** A finished script that made no client call and was not abandoned. */
    fun quiet(record: CodeModeRecord): Boolean =
        finished(record) && !record.abandoned() && record.clientIds().isEmpty()

    /** Whether a script that made no client call is still to be sent: nothing of it is in the client's history. */
    private fun unsent(record: CodeModeRecord, prior: List<JsonElement>, conversation: List<JsonElement>): Boolean {
        val ids = record.clientIds()
        val called = conversation.any { codec.callId(it) in ids }
        val placed = prior.any { codec.callId(it) == record.origin.outerCallId }
        return finished(record) && !called && !placed
    }

    /** An assistant message's text, or null for any other item. */
    fun text(item: JsonElement?): String? {
        val message = item as? JsonObject ?: return null
        if (codec.string(message, CODE_MODE_FIELD_ROLE) != ECHO_ROLE) return null
        return (message[ECHO_TEXT] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** A finished script that made no client call, the reasoning and preface it [replay]s, and how many of the items
 *  folded the history it ran on ends [at]. */
internal data class CodeModeQuiet(val record: CodeModeRecord, val replay: List<JsonElement>, val at: Int)

/** Where the client's copy of a script's reasoning and preface starts, and its [echo]. */
internal data class CodeModeCopy(val start: Int, val echo: CodeModeEcho)

/** Whether an item of the client's history is in the copy of a script other than the one being placed, which that
 *  script's copy search does not go past. */
internal fun interface CodeModeOtherCopy {
    fun holds(item: JsonElement): Boolean
}

/** Whether a script that made no client call is sent right after [prior], the history GPT is sent ahead of it. */
internal fun interface CodeModeScriptDue {
    fun after(record: CodeModeRecord, prior: List<JsonElement>): Boolean
}

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
    private val out = mutableListOf<JsonElement>()
    private val between = mutableListOf<JsonElement>()
    private val opened = mutableSetOf<String>()
    private val followUps = mutableSetOf<Int>()
    private val echoed = mutableSetOf<Int>()
    private val placements = HashMap<Int, MutableList<CodeModeRecord>>()
    private var open: CodeModeRecord? = null

    fun fold(client: List<JsonElement>): List<JsonElement> {
        val items = placed(client)
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
private const val REASONING_ID = "id"
