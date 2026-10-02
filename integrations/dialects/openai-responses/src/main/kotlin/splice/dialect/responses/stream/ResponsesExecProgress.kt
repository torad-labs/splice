// NEW: exec input feeds the runtime observer, never client prose or model reasoning.
package splice.dialect.responses.stream

import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeManual
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

private data class ExecProgressItem(val id: String?, val callId: String)

/** The real tool calls are the readable script. Quiet intervals use the head's signed wait notice. */
internal class ResponsesExecProgress {
    private val frames = ResponsesFrameParse()
    private val items = HashMap<Int, ExecProgressItem>()

    suspend fun added(item: JsonObject, outputIndex: Int, sink: WireSink) {
        close(outputIndex)
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

    suspend fun delta(event: JsonObject, sink: WireSink) {
        val outputIndex = frames.intOr(event[OUTPUT_INDEX]) ?: return
        val item = items[outputIndex] ?: return
        val eventId = JsonScalars.str(event, "item_id")
        val stale = eventId != null && item.id != null && eventId != item.id
        val text = JsonScalars.strOrEmpty(event[DELTA])
        if (!stale && text.isNotEmpty()) sink.customToolSource(CustomToolSource.Delta(item.callId, text))
    }

    fun close(outputIndex: Int) {
        items.remove(outputIndex)
    }

    fun closeAll() {
        items.clear()
    }
}
