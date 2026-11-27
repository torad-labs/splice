package splice.app.desktop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.model.ClientWindow
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

/** What a door turn asks: its body, the model it names, the session it is a turn of, and, for a request of Claude
 *  Desktop's app mode, what the request reveals about the Code tab's window ([appWindow]). */
internal data class DoorAsk(
    val request: JsonObject,
    val model: String,
    val session: String?,
    val appWindow: ClientWindow? = null,
    val appMode: Boolean = false,
) {
    /** Whether this is Claude Code's start-up check: one token, not streamed (desktop-door.md's table), whose answer
     *  no one reads. */
    val probe: Boolean
        get() = JsonScalars.long(request, REQUEST_MAX_TOKENS) == 1L &&
            JsonScalars.str(request, REQUEST_STREAM) != "true"

    /** The body as a head takes it: the same request, asking for [upstream], the ID that head runs the model as. */
    fun asking(upstream: String): ByteArray =
        JsonWire.bytes(JsonObject(request + (REQUEST_MODEL to JsonPrimitive(upstream))))
}

internal const val REQUEST_MODEL: String = "model"
private const val REQUEST_MAX_TOKENS = "max_tokens"
private const val REQUEST_STREAM = "stream"
