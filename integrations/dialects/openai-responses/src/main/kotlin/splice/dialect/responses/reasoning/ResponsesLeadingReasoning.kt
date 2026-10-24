package splice.dialect.responses.reasoning

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.util.JsonScalars
import splice.dialect.responses.stream.ResponsesExecProgress
import splice.dialect.responses.stream.ResponsesHarvest
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import splice.upstream.transport.BufferCapacity

/** Writes a settled readable literal into its reasoning item's client block. */
internal fun interface ReasoningLiteralWriter {
    suspend fun write(outputIndex: Int, text: String, sink: WireSink)
}

/** Owns ambiguous leading prefixes, their shared-capacity charge, and settlement at visible boundaries. */
internal class ResponsesLeadingReasoning(
    private val writer: ReasoningLiteralWriter,
) {
    private val prefixes = LinkedHashMap<Int, StringBuilder>()
    private val harvest = ResponsesHarvest()
    private var changed = false
    var pendingChars: Long = 0
        private set

    fun hold(oi: Int, release: SummaryRelease): String? {
        val held = prefixes.getOrPut(oi) { StringBuilder() }
        require(held.length.toLong() + release.text.length <= BufferCapacity.MAX_BUFFERED_CHARS) {
            "reasoning prefix exceeds buffer capacity"
        }
        held.append(release.text)
        pendingChars += release.text.length
        changed = true
        if (!release.canRender(held.toString())) return null
        return take(oi)
    }

    fun take(oi: Int): String? = remove(oi)?.toString()

    /** A completed raw snapshot overrides its held prefix; absent snapshots retain the literal bytes. */
    fun completed(item: JsonObject, oi: Int): String? {
        val held = take(oi) ?: return null
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

    private fun remove(oi: Int): StringBuilder? = prefixes.remove(oi)?.also {
        pendingChars -= it.length
        changed = true
    }

    /** Preserve arrival order; blank prefixes can still acquire a body after interleaved prose. */
    suspend fun settle(sink: WireSink, discardEmpty: Boolean) {
        if (!discardEmpty && !changed) return
        for (oi in prefixes.keys.toList()) {
            val text = checkNotNull(prefixes[oi]).toString()
            val readable = ReadableReasoning.hasText(text)
            if (readable || discardEmpty) remove(oi)
            if (readable) writer.write(oi, text, sink)
        }
        changed = false
    }

    /** Forget exec progress on every exit; parsed terminals settle prefixes before notifying their observer. */
    suspend fun closeSourceProgress(
        progress: ResponsesExecProgress,
        terminalSeen: Boolean,
        sink: WireSink,
    ) = withContext(NonCancellable) {
        progress.closeAll()
        if (terminalSeen) {
            settle(sink, discardEmpty = true)
            sink.customToolSource(CustomToolSource.Terminal)
        }
    }

    /** Settle a leading literal before a later visible phase, rather than after the answer at termination. */
    fun orderedSink(sink: WireSink): WireSink = object : WireSink by sink {
        override suspend fun openText(): WireBlockIndex {
            settle(sink, discardEmpty = false)
            return sink.openText()
        }

        override suspend fun textDelta(index: WireBlockIndex, text: String) {
            settle(sink, discardEmpty = false)
            sink.textDelta(index, text)
        }

        override suspend fun openTool(id: String, name: String): WireBlockIndex {
            settle(sink, discardEmpty = false)
            return sink.openTool(id, name)
        }

        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
            settle(sink, discardEmpty = false)
            sink.inputJsonDelta(index, partialJson)
        }

        override suspend fun customToolSource(event: CustomToolSource) {
            if (event is CustomToolSource.Completed) settle(sink, discardEmpty = false)
            sink.customToolSource(event)
        }
    }
}
