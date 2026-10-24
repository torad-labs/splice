// NEW: builds each code-mode request from the last one sent, the way Codex builds each request from what it recorded.
package splice.provider.codex.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.provider.codex.CODE_MODE_FIELD_INPUT
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeClientStart
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeHistoryCodec
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
        return extend(context, client, request, records - changed.toSet(), calls)
    }

    /** Whether a result [record] captured media for comes back with other media (one it never
     *  captured is not judged). */
    private fun replaysOtherMedia(record: CodeModeRecord, media: Map<String, List<JsonElement>>): Boolean =
        media.any { (id, replayed) -> record.accepted.media(id)?.let { it != replayed } == true }

    /** A script that finished without a client call follows the post it started on: none of it is in the client's
     *  history, so its reasoning and preface go first and the client replays them next ([CodeModeBody.echoes]).
     *  Folded from nothing, one the client has none of goes last only when it ran in the very request the client
     *  sends again (its reply was lost): a baseline anchor alone also matches a history the client edited. An
     *  unchanged history is the body the client sent, as it arrived. */
    private fun extend(
        context: CodeModeRunContext,
        client: CodeModeBody,
        request: Pair<JsonObject, JsonArray>,
        records: List<CodeModeRecord>,
        changed: Set<String>,
    ): CodeModeBody {
        val (root, input) = request
        val preamble = input.subList(0, preambleCount(input))
        val conversation = input.subList(preamble.size, input.size)
        val (entry, tail) = extended(context.key, conversation, records, changed)
        val fresh = conversation.size > entry.covered.size
        val quiet = if (entry === nothing) {
            scripts.measured(records, preamble + conversation, conversation, client.digests)
        } else {
            scripts.since(records, entry)
        }
        val folded = entry.sent + CodeModeScriptFold(codec, owners(records), quiet, scripts).fold(tail)
        val due = if (entry === nothing) {
            CodeModeScriptDue { record, prior ->
                record.progress.lastDigest == context.digest && scripts.startedOn(record, preamble + prior)
            }
        } else {
            CodeModeScriptDue { record, _ -> record.postedOn == entry.id }
        }
        val appended = scripts.appended(records, folded, conversation, due)
        val items = preamble + folded + appended.flatMap { scripts.continuity(it) + scripts.placement(it) }
        val echoes = (if (fresh) emptyList() else entry.echoes) + appended.flatMap(scripts::continuity)
        return if (items == input && echoes.isEmpty()) {
            client
        } else {
            val rebuilt = JsonObject(root + (CODE_MODE_FIELD_INPUT to JsonArray(items)))
            CodeModeBody(RoundBody.Tree(rebuilt), json, digests = client.digests, echoes = echoes)
        }
    }

    /** Where a script started now in [context]'s client history: after all of it, preamble aside. */
    fun clientStart(context: CodeModeRunContext): CodeModeClientStart? {
        val client = context.client ?: return null
        val input = client.request?.second ?: return null
        return CodeModeClientPrefix(input.subList(preambleCount(input), input.size), client.digests).start()
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
     *  non-empty addition starts with the post's echoes, which GPT was already sent and so are left out: the client
     *  replays them, joining a preface with the prose GPT gave after it when they came in one message. */
    private fun newItems(entry: CodeModeLedgerEntry, conversation: List<JsonElement>): List<JsonElement>? {
        val covered = entry.covered.size
        if (conversation.size < covered || conversation.subList(0, covered) != entry.covered) return null
        val tail = conversation.subList(covered, conversation.size)
        if (tail.isEmpty()) return tail
        return scripts.echo(tail, 0, entry.echoes)?.let { scripts.after(tail, it) }
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
