// NEW: builds each code-mode request from the last one sent, the way Codex builds each request from what it recorded.
package splice.provider.codex.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.provider.codex.CODE_MODE_FIELD_INPUT
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeClientStart
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.durable.CodeModeHistoryStorage
import splice.provider.codex.stream.batch.CodeModeBatchHistory
import splice.provider.codex.stream.batch.CodeModeBatchLinks
import splice.upstream.RoundBody

/**
 * GPT's history in code mode is Codex's: each exec call with its output, and none of the client calls a script
 * made. While the client's history only grows, a request is the conversation last sent plus the client's new
 * items, in order, with each finished script's calls folded to its exec call and output: the call where its first
 * client call stood, the output where its last result stood, and whatever arrived between them after the output.
 * Nothing already sent is rewritten, so the prompt cache covers all of it. A history that extends no kept post (a
 * rewind, a compaction or an edit) is the client's whole history folded the same way, once; its post is
 * kept from then on.
 */
internal class CodeModeUpstreamHistory(
    private val json: Json,
    location: CodeModeStateLocation? = null,
    private val log: LogSink = LogSink { },
    cacheBytes: Long = HISTORY_MAX_BYTES,
) {
    private val codec = CodexCodeModeHistoryCodec(json)
    private val ledger = CodeModeUpstreamLedger(
        storage = location?.let { CodeModeHistoryStorage(it, json, HISTORY_MAX_BYTES, log = log) },
        cacheBytes = cacheBytes,
    )
    private val clientDelta = CodeModeClientDelta(codec)
    private val durableClears = CodeModeDurableClears(ledger, clientDelta)
    private val scripts = CodeModeScriptPlacements(codec)
    private val clientStep = CodeModeClientStep(codec, scripts)

    /** No post at all: what a conversation that extends none is folded from. */
    private val nothing = CodeModeLedgerEntry(emptyList(), emptyList(), emptyList())

    /** The request for [context]: the kept post it extends with what the client added, or its whole history folded
     *  when it extends none. A record whose accepted result this request changed (a fork), or replays with other
     *  media than it captured (V4-179), is not folded: the client's calls are the history, and no post that sent
     *  it folded is extended, since its result is no longer what GPT was sent. Null only for a recovery request or
     *  one that is not a Responses input. */
    fun extend(context: CodeModeRunContext, records: List<CodeModeRecord>): CodeModeBody? {
        CodeModeBatchLinks.link(records)
        val client = context.client?.takeIf { context.recovery == null } ?: return null
        val request = client.request ?: return null
        val media = context.turn.rendering.replayMedia
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
     *  Folded from nothing, one the client has none of goes last only when it ran after all of the client's history
     *  (its reply was lost and the client sends that history again), whatever else of the request changed. An
     *  unchanged history is the body the client sent, as it arrived. */
    private fun extend(
        context: CodeModeRunContext,
        client: CodeModeBody,
        request: Pair<JsonObject, JsonArray>,
        records: List<CodeModeRecord>,
        changed: Set<String>,
    ): CodeModeBody {
        val (root, input) = request
        val preamble = input.subList(0, clientStep.preambleCount(input))
        val conversation = input.subList(preamble.size, input.size)
        val cleared = CodeModeCallbackReceipts.proof(context)
        val (entry, tail) = extended(context.key, conversation, records, changed, cleared)
        val fresh = conversation.size > entry.covered.size
        val quiet = if (entry === nothing) {
            scripts.measured(records, preamble + conversation, conversation, client.digests)
        } else {
            scripts.since(records, entry)
        }
        val folded = entry.sent + CodeModeScriptFold(codec, owners(records), quiet, scripts).fold(tail)
        val due = if (entry === nothing) {
            val last = quiet.filter { it.at == conversation.size }.mapTo(HashSet()) { it.record.id }
            CodeModeScriptDue { record, _ -> record.id in last }
        } else {
            CodeModeScriptDue { record, _ ->
                record.postedOn == entry.id && CodeModeRestorableClears.permits(record, cleared)
            }
        }
        val appended = scripts.appended(records, folded, conversation, due)
        val items = preamble + folded + appended.flatMap { scripts.continuity(it) + scripts.placement(it) }
        val echoes = (if (fresh) emptyList() else entry.echoes) + appended.flatMap {
            CodeModeScriptDelivery.items(
                it,
                scripts,
            )
        }
        return if (items == input && echoes.isEmpty()) {
            client
        } else {
            val rebuilt = JsonObject(root + (CODE_MODE_FIELD_INPUT to JsonArray(items)))
            CodeModeBody(RoundBody.Tree(rebuilt), json, digests = client.digests, echoes = echoes)
        }
    }

    fun begin(context: CodeModeRunContext, records: List<CodeModeRecord>) = clientStep.begin(context, records)

    fun clientStart(context: CodeModeRunContext): CodeModeClientStart? = clientStep.start(context)

    fun postedClientStart(context: CodeModeRunContext, record: CodeModeRecord): CodeModeClientAcknowledgement? =
        clientStep.postedStart(context, record, ledger.entries(context.key))

    /** Keeps [body] as what [context]'s conversation last sent, as it is posted. */
    fun sent(context: CodeModeRunContext, body: CodeModeBody) {
        val client = context.client?.request?.second?.takeIf { context.recovery == null }
        val posted = body.request?.second
        if (client == null || posted == null) {
            ledger.forget(context.key)
            return
        }
        val observed = client.subList(clientStep.preambleCount(client), client.size)
        val proof = CodeModeCallbackReceipts.proof(context)
        val prior = ledger.entries(context.key).firstOrNull { clientDelta.after(it, observed, proof) != null }
        val covered = prior?.let { clientDelta.normalized(it, observed, proof) } ?: observed
        val conversation = posted.subList(clientStep.preambleCount(posted), posted.size)
        val callbacks = CodeModeCallbackReceipts.capture(context, covered, prior?.callbacks.orEmpty())
        ledger.record(
            context.key,
            CodeModeLedgerEntry(covered, conversation, body.echoes, callbacks = callbacks),
            prior?.id,
        )
    }

    /** Durable callback receipts can verify a clear after its execution record was retired. */
    fun acknowledgeCleared(context: CodeModeRunContext) = acknowledgeCleared(context, emptyList())

    fun acknowledgeCleared(context: CodeModeRunContext, records: List<CodeModeRecord>) {
        val client = context.client?.request?.second ?: return
        durableClears.acknowledge(context, client.drop(clientStep.preambleCount(client)), records)
    }

    /** The newest kept post of a script started now: what [CodeModeRecord.postedOn] holds. */
    fun newest(key: String): Long? = ledger.newest(key)

    /** Ordinary response items extend the prepared input verbatim. Exec responses are committed by their durable
     * script record with its output, so a pending exec can never look like an already completed placement. */
    fun settled(context: CodeModeRunContext, outcome: TurnOutcome): TurnOutcome {
        val success = outcome as? TurnOutcome.Success ?: return outcome
        if (success.handoffs.customCalls.isEmpty()) {
            val output = success.handoffs.nativeOutput
            val id = ledger.newest(context.key)
            if (output != null && id != null) {
                ledger.output(context.key, id, output, CodeModeOutputDelivery.items(output))
            } else if (output == null) {
                log("[code-mode] authoritative history legacy-output key=${context.key.take(HISTORY_LOG_KEY_CHARS)}")
            }
        }
        return outcome
    }

    fun forget(key: String) = ledger.forget(key)

    /** The newest kept post [conversation] extends, and what the client added since. A post that sent one of the
     *  [changed] exec calls is not extended. An older post is extended only while no script started on a newer one,
     *  since that script was measured on the newer history. A conversation that extends none (a rewind, a
     *  compaction or an edit) is the client's whole history, folded from nothing, once; its post is kept
     *  from then on. */
    private fun extended(
        key: String,
        conversation: List<JsonElement>,
        records: List<CodeModeRecord>,
        changed: Set<String>,
        cleared: CodeModeCallbackProof,
    ): Pair<CodeModeLedgerEntry, List<JsonElement>> {
        val posts = ledger.entries(key)
        posts.forEachIndexed { index, entry ->
            if (entry.sent.any { codec.callId(it) in changed }) return@forEachIndexed
            val tail = clientDelta.after(entry, conversation, cleared) ?: return@forEachIndexed
            val newer = posts.subList(0, index).map(CodeModeLedgerEntry::id).toSet()
            val presented = tail.mapNotNullTo(mutableSetOf(), codec::callId)
            val needsNewer = records.any { record ->
                record.postedOn in newer && (record.clientIds() + record.origin.outerCallId).any { it in presented }
            }
            return if (needsNewer) nothing to conversation else entry to tail
        }
        if (posts.isEmpty()) {
            log("[code-mode] authoritative history seeded origin=legacy key=${key.take(HISTORY_LOG_KEY_CHARS)}")
        } else {
            log(
                "[code-mode] authoritative history branch key=${key.take(
                    HISTORY_LOG_KEY_CHARS,
                )} priorPosts=${posts.size}",
            )
        }
        return nothing to conversation
    }

    /** Each finished script's client call ids, mapped to it. A script still running keeps its calls as they are, and
     *  an abandoned one keeps them for good: its conversation went on upstream on them (V4-342). */
    private fun owners(records: List<CodeModeRecord>): Map<String, CodeModeRecord> = buildMap {
        records.filter { scripts.finished(it) && !it.abandoned() }
            .forEach { record ->
                val owner = CodeModeBatchHistory.owner(record)
                record.clientIds().forEach { put(it, owner) }
            }
    }
}

// A byte refusal is observable and fails closed; cache eviction never discards model authority.
private const val HISTORY_MAX_BYTES: Long = 256L * 1024 * 1024

// Limit diagnostic keys to a short non-content correlation prefix.
private const val HISTORY_LOG_KEY_CHARS = 8
