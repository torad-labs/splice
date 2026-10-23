// NEW: builds each code-mode request from the last one sent, the way Codex builds each request from what it recorded.
package splice.provider.codex.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CODE_MODE_FIELD_CALL_ID
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.state.CodeModeLedgerEntry
import splice.provider.codex.state.CodeModeUpstreamLedger
import splice.upstream.RoundBody

/**
 * GPT's history in code mode is Codex's: each exec call with its output, and none of the client calls a script
 * made. While the client's history only grows, a request is the conversation last sent plus the client's new
 * items, in order, with each finished script's calls folded to its exec call and output: the call where its first
 * client call stood, the output where its last result stood, and whatever arrived between them after the output.
 * Nothing already sent is rewritten, so the prompt cache covers all of it. A history that extends no kept post (a
 * rewind, a compaction, an edit, a restart) is the client's whole history folded the same way, once; its post is
 * kept from then on.
 */
internal class CodeModeUpstreamHistory(private val json: Json) {
    private val codec = CodexCodeModeHistoryCodec(json)
    private val ledger = CodeModeUpstreamLedger()
    private val scripts = CodeModeScriptPlacements(codec)

    /** No post at all: what a conversation that extends none is folded from. */
    private val nothing = CodeModeLedgerEntry(emptyList(), emptyList(), emptyList())

    /** The request for [context]: the kept post it extends with what the client added, or its whole history folded
     *  when it extends none. A record whose accepted result this request changed (a fork), or replays with other
     *  media than it captured (V4-179), is not folded: the client's calls are the history, and no post that sent
     *  it folded is extended, since its result is no longer what GPT was sent. Null only for a recovery request or
     *  one that is not a Responses input. */
    fun extend(context: CodeModeRunContext, records: List<CodeModeRecord>): CodeModeBody? {
        val client = context.client?.takeIf { context.recovery == null } ?: return null
        val request = client.request ?: return null
        val media = context.turn.rendering.media
        val changed = records.filter { it.id in context.conflicts || replaysOtherMedia(it, media) }
        val calls = changed.mapTo(HashSet()) { it.origin.outerCallId }
        return extend(context.key, client, request, records - changed.toSet(), calls)
    }

    /** Whether a result [record] captured media for comes back with other media (one it never
     *  captured is not judged). */
    private fun replaysOtherMedia(record: CodeModeRecord, media: Map<String, List<JsonElement>>): Boolean =
        media.any { (id, replayed) -> record.accepted.media(id)?.let { it != replayed } == true }

    /** A script that finished without a client call follows the post it started on: none of it is in the client's
     *  history, so its reasoning and preface go first and the client replays them next ([CodeModeBody.echoes]). An
     *  unchanged history is the body the client sent, as it arrived. */
    private fun extend(
        key: String,
        client: CodeModeBody,
        request: Pair<JsonObject, JsonArray>,
        records: List<CodeModeRecord>,
        changed: Set<String>,
    ): CodeModeBody {
        val (root, input) = request
        val preamble = input.subList(0, preambleCount(input))
        val conversation = input.subList(preamble.size, input.size)
        val (entry, tail) = extended(key, conversation, records, changed)
        val replayed = if (tail.isEmpty()) 0 else entry.echoes.size
        val echoed = scripts.silent(records, entry.sent)
        val folded = entry.sent + CodeModeScriptFold(codec, owners(records), echoed, scripts).fold(tail.drop(replayed))
        val due = if (entry === nothing) {
            CodeModeScriptDue { record, prior -> scripts.startedOn(record, preamble + prior) }
        } else {
            CodeModeScriptDue { record, _ -> record.postedOn == entry.id }
        }
        val appended = scripts.appended(records, folded, conversation, due)
        val items = preamble + folded + appended.flatMap { scripts.continuity(it) + scripts.placement(it) }
        val echoes = (if (tail.isEmpty()) entry.echoes else emptyList()) + appended.flatMap(scripts::continuity)
        return if (items == input && echoes.isEmpty()) {
            client
        } else {
            val rebuilt = JsonObject(root + (FIELD_INPUT to JsonArray(items)))
            CodeModeBody(RoundBody.Tree(rebuilt), json, digests = client.digests, echoes = echoes)
        }
    }

    /** Keeps [body] as what [context]'s conversation last sent, as it is posted. */
    fun sent(context: CodeModeRunContext, body: CodeModeBody) {
        val client = context.client?.request?.second?.takeIf { context.recovery == null }
        val posted = body.request?.second
        if (client == null || posted == null) {
            ledger.forget(context.key)
            return
        }
        val covered = client.subList(preambleCount(client), client.size)
        val conversation = posted.subList(preambleCount(posted), posted.size)
        ledger.record(context.key, CodeModeLedgerEntry(covered, conversation, body.echoes))
    }

    /** The newest kept post of a script started now: what [CodeModeRecord.postedOn] holds. */
    fun newest(key: String): Long? = ledger.newest(key)

    /** A post upstream refused for good is not kept: the next request falls back to the client's history. */
    fun settled(context: CodeModeRunContext, outcome: TurnOutcome): TurnOutcome {
        if (outcome is TurnOutcome.Failure && outcome.traits.permanent) ledger.forget(context.key)
        return outcome
    }

    fun forget(key: String) = ledger.forget(key)

    /** The newest kept post [conversation] extends, and what the client added since. A post that sent one of the
     *  [changed] exec calls is not extended. An older post is extended only while no script started on a newer one,
     *  since that script was measured on the newer history. A conversation that extends none (a rewind, a
     *  compaction, an edit, a restart) is the client's whole history, folded from nothing, once; its post is kept
     *  from then on. */
    private fun extended(
        key: String,
        conversation: List<JsonElement>,
        records: List<CodeModeRecord>,
        changed: Set<String>,
    ): Pair<CodeModeLedgerEntry, List<JsonElement>> {
        val posts = ledger.entries(key)
        posts.forEachIndexed { index, entry ->
            if (entry.sent.any { codec.callId(it) in changed }) return@forEachIndexed
            val tail = newItems(entry, conversation) ?: return@forEachIndexed
            val newer = posts.subList(0, index).map(CodeModeLedgerEntry::id).toSet()
            return if (records.none { it.postedOn in newer }) entry to tail else nothing to conversation
        }
        return nothing to conversation
    }

    /** What the client added since [entry]'s post, or null when its conversation does not extend that post's. A
     *  non-empty addition starts with the post's echoes, which it replays. */
    private fun newItems(entry: CodeModeLedgerEntry, conversation: List<JsonElement>): List<JsonElement>? {
        val covered = entry.covered.size
        if (conversation.size < covered || conversation.subList(0, covered) != entry.covered) return null
        val tail = conversation.subList(covered, conversation.size)
        return tail.takeIf { it.isEmpty() || codec.continuityAt(it, 0, entry.echoes) }
    }

    /** How many leading items of [input] are the lite preamble, which is environment and not history. */
    private fun preambleCount(input: List<JsonElement>): Int = input.takeWhile(codec::isPreamble).size

    /** Each finished script's client call ids, mapped to it. A script still running keeps its calls as they are, and
     *  an abandoned one keeps them for good: its conversation went on upstream on them (V4-342). */
    private fun owners(records: List<CodeModeRecord>): Map<String, CodeModeRecord> = buildMap {
        records.filter { scripts.finished(it) && !it.abandoned() }
            .forEach { record -> record.clientIds().forEach { put(it, record) } }
    }
}

/**
 * Where a finished script that made no client call goes, since nothing of it is in the client's history but the
 * reasoning and preface it replays: after that replay when the client has it, else after the post the script
 * started on, or, once splice no longer holds that post, after the history the script was measured on when that is
 * the whole history (a request retried after its reply was lost, a restart).
 */
private class CodeModeScriptPlacements(private val codec: CodexCodeModeHistoryCodec) {
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

    /** Whether a script that made no client call is still to be sent: nothing of it is in the client's history. */
    private fun unsent(record: CodeModeRecord, prior: List<JsonElement>, conversation: List<JsonElement>): Boolean {
        val ids = record.clientIds()
        val called = conversation.any { codec.callId(it) in ids }
        val placed = prior.any { codec.callId(it) == record.origin.outerCallId }
        return finished(record) && !called && !placed
    }
}

/** Whether a script that made no client call is sent right after [prior], the history GPT is sent ahead of it. */
private fun interface CodeModeScriptDue {
    fun after(record: CodeModeRecord, prior: List<JsonElement>): Boolean
}

/** One pass over the client's new items, folding each finished script's calls into its exec call and output, and
 *  sending each script that made no client call right after the reasoning and preface the client replays for it. */
private class CodeModeScriptFold(
    private val codec: CodexCodeModeHistoryCodec,
    private val owners: Map<String, CodeModeRecord>,
    echoed: List<Pair<CodeModeRecord, List<JsonElement>>>,
    private val scripts: CodeModeScriptPlacements,
) {
    private val out = mutableListOf<JsonElement>()
    private val between = mutableListOf<JsonElement>()
    private val opened = mutableSetOf<String>()
    private val followUps = mutableSetOf<Int>()
    private val unplaced = echoed.toMutableList()
    private var open: CodeModeRecord? = null

    fun fold(items: List<JsonElement>): List<JsonElement> {
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

    private fun plain(items: List<JsonElement>, index: Int) {
        val into = if (open != null) between else out
        into += items[index]
        val placed = unplaced.firstOrNull { (_, replay) ->
            index + 1 >= replay.size && codec.continuityAt(items, index + 1 - replay.size, replay)
        } ?: return
        unplaced.remove(placed)
        into += scripts.placement(placed.first)
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

private const val FIELD_INPUT = "input"
private const val TYPE_CALL = "function_call"
private const val TYPE_RESULT = "function_call_output"
