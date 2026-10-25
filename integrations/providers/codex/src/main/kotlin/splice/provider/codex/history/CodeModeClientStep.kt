package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeClientStart
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeHistoryCodec
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
