// NEW: exec input feeds the runtime observer and streams to the client as a splice-signed live block.
//
// V4-456 (Oct 2): the client sees the script while the model writes it. Oct 1's live-source change
// (ea17bb93c) kept only the runtime feed, so a code-mode step showed nothing until the runtime could
// dispatch a call and then arrived at once when the script ended: a GPT session's client got a 13-byte
// keepalive every 2 s for 55 s while the provider streamed the script, then 10 KB in one second.
// The block carries splice's notice signature, which every request parser drops, so the script is
// never replayed as the model's reasoning.
package splice.dialect.responses.stream

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.turn.GatewayCustomCall
import splice.core.turn.SpliceNotice
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeManual
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

private data class ExecProgressItem(val id: String?, val callId: String) {
    var block: WireBlockIndex? = null
}

/** Only an added exec item renders input. Its live block is signed and closed before any replacement. */
internal class ResponsesExecProgress {
    private val frames = ResponsesFrameParse()
    private val items = HashMap<Int, ExecProgressItem>()

    suspend fun added(item: JsonObject, outputIndex: Int, sink: WireSink) {
        close(outputIndex, sink)
        val isExec = JsonScalars.str(item, "type") == "custom_tool_call" &&
            JsonScalars.str(item, "name") == CodeModeManual.TOOL_NAME
        if (isExec) {
            val callId = JsonScalars.strOrEmpty(item["call_id"])
            items[outputIndex] = ExecProgressItem(JsonScalars.str(item, "id"), callId)
            sink.customToolSource(
                CustomToolSource.Started(
                    GatewayCustomCall(callId, CodeModeManual.TOOL_NAME, JsonScalars.strOrEmpty(item["input"]), item),
                ),
            )
        }
    }

    /** The runtime observes the text first, so showing it never delays a dispatch. */
    suspend fun delta(event: JsonObject, sink: WireSink) {
        val outputIndex = frames.intOr(event[OUTPUT_INDEX]) ?: return
        val item = items[outputIndex] ?: return
        val eventId = JsonScalars.str(event, "item_id")
        val stale = eventId != null && item.id != null && eventId != item.id
        val text = JsonScalars.strOrEmpty(event[DELTA])
        if (stale || text.isEmpty()) return
        sink.customToolSource(CustomToolSource.Delta(item.callId, text))
        val block = item.block ?: sink.openNotice().also { item.block = it }
        sink.thinkingDelta(block, text)
    }

    suspend fun close(outputIndex: Int, sink: WireSink) {
        val item = items.remove(outputIndex) ?: return
        item.block?.let { block ->
            // The same client-only signature as splice's wait notice: request parsers drop the block.
            sink.signatureDelta(block, SpliceNotice.SIGNATURE)
            sink.closeBlock(block)
        }
    }

    suspend fun closeAll(sink: WireSink) {
        items.keys.toList().forEach { close(it, sink) }
    }
}
