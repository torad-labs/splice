// NEW: client-visible exec input progress, signed for replay removal and never buffered as model reasoning.
package splice.dialect.responses.stream

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.turn.SpliceNotice
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeManual
import splice.upstream.sse.WireSink

private data class ExecProgressItem(val id: String?) {
    var block: WireBlockIndex? = null
}

/** Only an added exec item can render input. Its live block closes before any replacement or tool call. */
internal class ResponsesExecProgress {
    private val frames = ResponsesFrameParse()
    private val items = HashMap<Int, ExecProgressItem>()

    suspend fun added(item: JsonObject, outputIndex: Int, sink: WireSink) {
        close(outputIndex, sink)
        val isExec = JsonScalars.str(item, "type") == "custom_tool_call" &&
            JsonScalars.str(item, "name") == CodeModeManual.TOOL_NAME
        if (isExec) items[outputIndex] = ExecProgressItem(JsonScalars.str(item, "id"))
    }

    suspend fun delta(event: JsonObject, sink: WireSink) {
        val outputIndex = frames.intOr(event[OUTPUT_INDEX]) ?: return
        val item = items[outputIndex] ?: return
        val eventId = JsonScalars.str(event, "item_id")
        val stale = eventId != null && item.id != null && eventId != item.id
        val text = JsonScalars.strOrEmpty(event[DELTA])
        if (!stale && text.isNotEmpty()) {
            val block = item.block ?: sink.openThinking().also { item.block = it }
            sink.thinkingDelta(block, text)
        }
    }

    suspend fun close(outputIndex: Int, sink: WireSink) {
        val item = items[outputIndex] ?: return
        item.block?.let { block ->
            // The same client-only signature used by splice's live wait notices. Request parsers drop it.
            sink.signatureDelta(block, SpliceNotice.SIGNATURE)
            sink.closeBlock(block)
        }
        items.remove(outputIndex)
    }

    suspend fun closeAll(sink: WireSink) {
        items.keys.toList().forEach { close(it, sink) }
    }
}
