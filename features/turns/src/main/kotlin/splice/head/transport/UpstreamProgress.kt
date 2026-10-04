// NEW: protocol progress renews the turn deadline independently of visible-content telemetry.
package splice.head.transport

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.retry.TurnWatchdog

internal object UpstreamProgress {
    fun observe(event: JsonObject, watchdog: TurnWatchdog) {
        if (advances(event)) watchdog.progress()
    }

    private fun advances(event: JsonObject): Boolean {
        val kind = UpstreamEventKinds.kind(event)
        if (UpstreamEventKinds.hasContent(event, kind)) return true
        return when (JsonScalars.str(event, "type")) {
            "response.output_item.added", "response.output_item.done" ->
                JsonScalars.str(event["item"] as? JsonObject, "type") in PROGRESS_ITEMS
            "content_block_start", "content_block_stop" -> true
            else -> false
        }
    }
}

private val PROGRESS_ITEMS = setOf("reasoning", "message", "function_call", "custom_tool_call")
