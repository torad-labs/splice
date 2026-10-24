// NEW: locates script continuity and client echoes independently of folding callbacks into exec history.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.InputItemDigests
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
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
            .map { CodeModeQuiet(it, CodeModeScriptDelivery.items(it, this), 0) }
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
        listOf(CodeModeCallReplay.item(record)) + record.carry.nativeAfterExec.orEmpty() +
            record.progress.nativeNotifications + codec.customOutput(record) + record.accepted.durableMedia()

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

private const val ECHO_ROLE = "assistant"
private const val ECHO_TEXT = "content"
private const val REASONING_ID = "id"

/** Delivery witnesses include actual progress; canonical replay retains the separate notification items. */
internal object CodeModeScriptDelivery {
    fun items(record: CodeModeRecord, scripts: CodeModeScriptPlacements): List<JsonElement> =
        scripts.continuity(record) + record.deliveredOnly
}
