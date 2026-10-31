package splice.provider.codex.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeClientStart
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.branch.CodeModeClientOutputDigests
import splice.provider.codex.stream.batch.CodeModeBatchLinks
import java.lang.ref.WeakReference

/** Delivery acknowledged on this client step, separate from the model's durable posted history. */
internal class CodeModeClientStep(
    private val codec: CodexCodeModeHistoryCodec,
    private val scripts: CodeModeScriptPlacements,
) {
    /** Older records retain the exact post they started on even when their client start was not saved. */
    fun postedStart(
        context: CodeModeRunContext,
        record: CodeModeRecord,
        posts: List<CodeModeLedgerEntry>,
    ): CodeModeClientAcknowledgement? {
        if (record.key != context.key || record.sessionId != context.turn.sessionId) return null
        val post = posts.firstOrNull { it.id == record.postedOn } ?: return null
        return CodeModeClientAcknowledgements.capture(post.covered, post.callbacks.mapValues { it.value.media })
    }

    /** Whether a result [record] captured media for comes back with other media (one it never
     *  captured is not judged). */
    fun replaysOtherMedia(record: CodeModeRecord, media: Map<String, List<JsonElement>>): Boolean =
        media.any { (id, replayed) -> record.accepted.media(id)?.let { it != replayed } == true }

    /** Classify a resume against its original client prefix, removing only exact older accepted callback receipts.
     *  Their restored positions still reach model history; this view is used only for steering detection. */
    fun resumeInput(
        context: CodeModeRunContext,
        record: CodeModeRecord,
        posts: List<CodeModeLedgerEntry>,
    ): List<JsonElement>? {
        val prefix = resumePrefix(context, record, posts) ?: return null
        val normalized = normalizedResume(context, prefix, posts) ?: return null
        val projected = codec.conversation(codec.projection.project(JsonArray(normalized))).body
        val cursor = prefix.acknowledgement.consume(projected)
        return if (cursor == null) {
            null
        } else {
            normalized.takeUnless {
                projected.replayItems.any { replay ->
                    replay.logicalOffset < cursor.item && replay.items.any { !prefix.acknowledgement.owns(it) }
                }
            }
        }
    }

    private fun resumePrefix(
        context: CodeModeRunContext,
        record: CodeModeRecord,
        posts: List<CodeModeLedgerEntry>,
    ): ResumePrefix? {
        val input = context.client?.request?.second ?: return null
        val acknowledgement = record.replayAnchors?.client?.acknowledgement ?: return null
        return posts.firstOrNull { it.id == record.postedOn }?.let { ResumePrefix(input, acknowledgement, it) }
    }

    private fun normalizedResume(
        context: CodeModeRunContext,
        prefix: ResumePrefix,
        posts: List<CodeModeLedgerEntry>,
    ): List<JsonElement>? {
        val input = prefix.input
        val post = prefix.post
        val offset = preambleCount(input)
        val extends = post.covered.size <= input.size - offset &&
            post.covered.indices.all { input[offset + it] == post.covered[it] }
        if (extends) return null
        val placed = post.covered.mapNotNullTo(mutableSetOf(), codec::callId)
        val removed = mutableSetOf<Int>()
        context.completed.filterNot { it.abandoned() }.forEach { earlier ->
            earlier.results.keys.filterNot { it in placed }.forEach { id ->
                removed += restored(input, posts, earlier, id)
            }
        }
        if (removed.isEmpty()) return null
        return input.filterIndexed { index, _ -> index !in removed }
    }

    private fun restored(
        input: List<JsonElement>,
        posts: List<CodeModeLedgerEntry>,
        record: CodeModeRecord,
        id: String,
    ): Set<Int> {
        val call = CodeModeCallbackReceipts.items(input, "function_call")[id]?.singleOrNull() ?: return emptySet()
        val output = CodeModeCallbackReceipts.items(
            input,
            "function_call_output",
        )[id]?.singleOrNull() ?: return emptySet()
        return restoredReceipt(posts, record, id, call, output)?.let { receipt ->
            restoredIndexes(input, call, output, receipt.media)
        }.orEmpty()
    }

    private fun restoredReceipt(
        posts: List<CodeModeLedgerEntry>,
        record: CodeModeRecord,
        id: String,
        call: JsonElement,
        output: JsonObject,
    ): CodeModeCallbackReceipt? {
        val accepted = record.results[id] ?: return null
        val callDigest = InputDigest.hex(call)
        val outputDigest = InputDigest.hex(output)
        val expected = CodeModeClientOutputDigests.replay(
            record.accepted.clientOutputDigest(id),
            output,
            accepted.output,
        )
        if (outputDigest != expected) return null
        return posts.mapNotNull { it.callbacks[id] }.distinct().singleOrNull()?.takeIf {
            it.callDigest == callDigest && it.outputDigest == outputDigest
        }?.takeIf {
            it.isError == accepted.isError && it.media == record.accepted.media(id)
        }
    }

    private fun restoredIndexes(
        input: List<JsonElement>,
        call: JsonElement,
        output: JsonElement,
        media: List<JsonElement>,
    ): Set<Int> {
        val at = input.indexOf(output)
        val end = at + 1 + media.size
        val callAt = input.indexOf(call)
        if (callAt >= at) return emptySet()
        if (end > input.size) return emptySet()
        return if (input.subList(at + 1, end) == media) setOf(callAt) + (at until end) else emptySet()
    }

    private data class ResumePrefix(
        val input: List<JsonElement>,
        val acknowledgement: CodeModeClientAcknowledgement,
        val post: CodeModeLedgerEntry,
    )

    /** Capture existing delivery before an active script resumes or a new script starts on this client step. */
    fun begin(context: CodeModeRunContext, records: List<CodeModeRecord>) {
        CodeModeBatchLinks.link(records)
        context.link.priorDeliveryCounts = records.associate { it.id to CodeModeScriptDelivery.items(it, scripts).size }
    }

    /** Where a script started now in [context]'s client history: after all of it, preamble aside. A recovery posts
     *  the torn round's partial and a private marker after it, which the client never holds: its script started after
     *  the request the client sent. */
    fun start(context: CodeModeRunContext): CodeModeClientStart? {
        val client = context.recovery?.baseline ?: context.client ?: return null
        val base = started?.takeIf { it.first.get() === client }?.second
            ?: read(client, context.turn.rendering.replayMedia)?.also { started = WeakReference(client) to it }
        val delivered = context.completed.flatMap {
            CodeModeScriptDelivery.items(it, scripts).drop(context.link.priorDeliveryCounts[it.id] ?: 0)
        }
        if (base == null || delivered.isEmpty()) return base
        val input = checkNotNull(client.request).second.dropWhile(codec::isPreamble)
        return base.copy(
            acknowledgement = CodeModeClientAcknowledgements.capture(
                input + delivered,
                context.turn.rendering.replayMedia,
            ),
        )
    }

    /** Where a script starting now starts in [client]'s history, or null when it is not a Responses input. */
    private fun read(client: CodeModeBody, media: Map<String, List<JsonElement>>): CodeModeClientStart? {
        val input = client.request?.second ?: return null
        return CodeModeClientPrefix(input.subList(preambleCount(input), input.size), client.digests, media).start()
    }

    /** The body the last start was read from, and that start: every script one request starts reads it once. */
    @Volatile private var started: Pair<WeakReference<CodeModeBody>, CodeModeClientStart>? = null

    /** Leading environment items precede both client acknowledgement and durable conversation history. */
    fun preambleCount(input: List<JsonElement>): Int = input.takeWhile(codec::isPreamble).size
}
