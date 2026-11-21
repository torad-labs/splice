// NEW 2026-11-19 (reply-stream): the server-sent events one client frame holds, for the live reply's reader. A frame
// splice writes is one event as a rule; a frame holding several is read as several.
package splice.head.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

private const val SSE_EVENT_FIELD = "event:"
private const val SSE_DATA_FIELD = "data:"

/** One server-sent event: its type (the data's own, else the event line's) and its data. */
internal data class FrameEvent(val type: String, val data: JsonObject)

internal object FrameEvents {
    /** Each event in [frame], in order. An event whose data is not a JSON object is skipped. */
    fun of(frame: String): List<FrameEvent> {
        val events = ArrayList<FrameEvent>(1)
        var name = ""
        val payload = StringBuilder()
        for (line in frame.lineSequence()) {
            when {
                line.isEmpty() -> {
                    parsed(name, payload)?.let(events::add)
                    name = ""
                    payload.setLength(0)
                }
                line.startsWith(SSE_EVENT_FIELD) -> name = line.substring(SSE_EVENT_FIELD.length).trim()
                line.startsWith(SSE_DATA_FIELD) -> {
                    if (payload.isNotEmpty()) payload.append('\n')
                    payload.append(line.substring(SSE_DATA_FIELD.length).removePrefix(" "))
                }
            }
        }
        parsed(name, payload)?.let(events::add)
        return events
    }

    private fun parsed(name: String, payload: StringBuilder): FrameEvent? {
        if (payload.isEmpty()) return null
        val event = JsonScalars.objectOrNull(Json, payload.toString()) ?: return null
        return FrameEvent(JsonScalars.str(event, "type") ?: name, event)
    }
}
