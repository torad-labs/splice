package splice.dialect.responses.reasoning

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.util.JsonScalars
import splice.dialect.responses.stream.ResponsesHarvest
import splice.dialect.responses.stream.ResponsesItemFold
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import splice.upstream.transport.BufferCapacity

/** Writes a settled readable literal into its reasoning item's client block. */
internal fun interface ReasoningLiteralWriter {
    suspend fun write(outputIndex: Int, text: String, sink: WireSink)
}

/** Writes a settled summary through the fold's existing paragraph and header policies. */
internal fun interface ReasoningSummaryWriter {
    suspend fun write(outputIndex: Int, release: SummaryRelease, sink: WireSink)
}

/** Owns ambiguous leading prefixes, their shared-capacity charge, and settlement at visible boundaries. */
internal class ResponsesLeadingReasoning(
    private val writer: ReasoningLiteralWriter,
    private val summaryWriter: ReasoningSummaryWriter,
    private val summaries: SummaryStatusHeaders,
    private val sequence: ReasoningPrefixSequence,
) {
    private val prefixes = LinkedHashMap<Int, ReasoningPrefixText>()
    private val harvest = ResponsesHarvest()
    private var changed = false
    private var rawPendingChars = 0L
    private var summaryRevision = -1L
    val pendingChars: Long get() = rawPendingChars + summaries.pendingChars

    fun hold(oi: Int, release: SummaryRelease): String? {
        val held = prefixes.getOrPut(oi) { ReasoningPrefixText(sequence.next()) }
        require(held.length.toLong() + release.text.length <= BufferCapacity.MAX_BUFFERED_CHARS) {
            "reasoning prefix exceeds buffer capacity"
        }
        held.append(release.text)
        rawPendingChars += release.text.length
        changed = true
        if (!release.canRender(held)) return null
        return remove(oi)?.toString()
    }

    /** A completed raw snapshot overrides its held prefix; absent snapshots retain the literal bytes. */
    fun completed(item: JsonObject, oi: Int): String? {
        val held = remove(oi)?.toString() ?: return null
        return if (hasRawSnapshot(item)) harvest.reasoningReadableText(item) else held
    }

    private fun hasRawSnapshot(item: JsonObject): Boolean =
        listOf("content", "text", "reasoning", "reasoning_content").any { key ->
            when (val value = item[key]) {
                is JsonArray -> value.isNotEmpty()
                is JsonObject -> value.isNotEmpty()
                is JsonPrimitive -> JsonScalars.strOrEmpty(value).isNotEmpty()
                null -> false
            }
        }

    private fun remove(oi: Int): ReasoningPrefixText? = prefixes.remove(oi)?.also {
        rawPendingChars -= it.length
        changed = true
    }

    /** Preserve arrival order; a real visible phase or an ending also discards blank/comment-only prefixes. */
    suspend fun settle(sink: WireSink, discardEmpty: Boolean) {
        if (pendingChars == 0L) return
        if (!discardEmpty && !changed) {
            if (summaryRevision == summaries.revision) return
        }
        val pending = prefixes.map { (key, text) -> HeldReasoningPrefix(key, summary = false, text) } +
            summaries.pending()
        for (held in pending.sortedBy { it.text.order }) settlePrefix(held, sink, discardEmpty)
        changed = false
        summaryRevision = summaries.revision
    }

    private suspend fun settlePrefix(held: HeldReasoningPrefix, sink: WireSink, discardEmpty: Boolean) {
        if (!held.text.readable && !discardEmpty) return
        if (held.summary) {
            summaries.atBoundary(held.key, held.summaryIndex)?.let { summaryWriter.write(held.key, it, sink) }
        } else {
            remove(held.key)
            if (held.text.readable) writer.write(held.key, held.text.toString(), sink)
        }
    }

    /** Forget exec progress on every exit; parsed terminals settle prefixes before notifying their observer. */
    suspend fun closeSourceProgress(
        itemFold: ResponsesItemFold,
        terminalSeen: Boolean,
        sink: WireSink,
        capacityRejected: Boolean = false,
    ) = withContext(NonCancellable) {
        itemFold.execProgress.closeAll()
        if (capacityRejected) discardPending()
        if (terminalSeen) {
            settle(sink, discardEmpty = true)
            itemFold.continuity(sink)
            sink.customToolSource(CustomToolSource.Terminal)
        }
    }

    private fun discardPending() {
        prefixes.clear()
        rawPendingChars = 0
        summaries.discardPending()
        changed = false
        summaryRevision = summaries.revision
    }

    /** Settle a leading literal before a later visible phase, rather than after the answer at termination. */
    fun orderedSink(sink: WireSink): WireSink = object : WireSink by sink {
        override suspend fun openText(): WireBlockIndex {
            settle(sink, discardEmpty = true)
            return sink.openText()
        }

        override suspend fun textDelta(index: WireBlockIndex, text: String) {
            settle(sink, discardEmpty = true)
            sink.textDelta(index, text)
        }

        override suspend fun openTool(id: String, name: String): WireBlockIndex {
            settle(sink, discardEmpty = true)
            return sink.openTool(id, name)
        }

        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
            settle(sink, discardEmpty = true)
            sink.inputJsonDelta(index, partialJson)
        }

        override suspend fun customToolSource(event: CustomToolSource) {
            if (event is CustomToolSource.Completed) settle(sink, discardEmpty = true)
            sink.customToolSource(event)
        }
    }
}
