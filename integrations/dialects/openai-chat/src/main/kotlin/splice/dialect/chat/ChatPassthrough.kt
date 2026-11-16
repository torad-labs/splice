// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). An OpenAI-style client's own chat request, relayed
// to the provider's server as the client sent it, and the two places the relay touches it. A streamed request always
// asks the server for its usage (stream_options.include_usage), because the server's operator needs what a turn
// used whether or not its client asked; and that usage reaches the client only when it asked. OpenAI's API sends it
// on a frame of its own, with no choice, which a client that did not ask never gets. Some servers put it on the
// frame that finishes the answer instead, and a client that did not ask gets that frame without it. Everything
// else, in both directions, is the client's and the server's own bytes. A stream the server ended cleanly without
// `[DONE]` is given that line, which every OpenAI client reads as the end.
package splice.dialect.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

/** One chat request as it goes on to the server: its [body], and whether its answer is a stream. */
public class PassedChat internal constructor(
    public val body: ByteArray,
    public val streamed: Boolean,
    private val usageAsked: Boolean,
    /** The model the client asked for, or null when its body names none. */
    public val model: String? = null,
) {
    /** What the server's answer to this request used, read as it is relayed (server mode's ledger, F5). */
    public val meter: ChatAnswerMeter = ChatAnswerMeter()

    /** The reader of this request's stream, which knows whether its client asked for usage. */
    public fun stream(): PassedChatStream = PassedChatStream(usageAsked, meter)
}

public class ChatPassthrough {
    /** [sent] as the server gets it. A body that asks for no stream, or is no JSON object, goes on byte for byte; a
     *  streamed one goes on with `stream_options.include_usage` true and every other field as the client wrote it. */
    public fun request(sent: ByteArray): PassedChat {
        val read = objectOf(String(sent))
        val model = JsonScalars.str(read, "model")
        val asked = read?.takeIf { it[STREAM_FLAG] == JsonPrimitive(true) }
            ?: return PassedChat(sent, streamed = false, usageAsked = false, model)
        val options = asked[STREAM_OPTIONS] as? JsonObject
        val usageAsked = options?.get(INCLUDE_USAGE) == JsonPrimitive(true)
        val withUsage = JsonObject(options.orEmpty() + (INCLUDE_USAGE to JsonPrimitive(true)))
        val body = JsonWire.bytes(JsonObject(asked + (STREAM_OPTIONS to withUsage)))
        return PassedChat(body, streamed = true, usageAsked, model)
    }

    internal fun objectOf(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        // Not JSON: the server is the one to refuse it, in its own words.
        null
    }
}

/** One relayed chat stream, read event by event. */
public class PassedChatStream internal constructor(
    private val usageAsked: Boolean,
    private val meter: ChatAnswerMeter,
) {
    private val json = ChatPassthrough()
    private var done = false

    /** [event], one event of the server's stream without the blank line that ends it, as the client gets it: itself,
     *  unless it carries usage this client did not ask for. Then the frame that carries nothing else is null, and one
     *  that carries a choice too is that frame without its usage. */
    public fun event(event: String): String? {
        val data = event.lineSequence().filter { it.startsWith(DATA_FIELD) }
            .joinToString("\n") { it.removePrefix(DATA_FIELD).trim() }
        if (data == CHAT_DONE) done = true
        meter.data(data)
        val frame = unasked(data) ?: return event
        if ((frame[FRAME_CHOICES] as? JsonArray).isNullOrEmpty()) return null
        val plain = "$DATA_FIELD ${JsonWire.string(JsonObject(frame - FRAME_USAGE))}"
        return (event.lineSequence().filterNot { it.startsWith(DATA_FIELD) } + plain).joinToString("\n")
    }

    /** What ends a stream the server ended cleanly: `data: [DONE]` when the server sent none of its own, else null. */
    public fun ending(): String? {
        meter.closed()
        return "$DATA_FIELD $CHAT_DONE".takeUnless { done }
    }

    /** [data] as the frame it is, when it carries usage this stream's client did not ask for; else null. */
    private fun unasked(data: String): JsonObject? =
        if (usageAsked) null else json.objectOf(data)?.takeIf { it[FRAME_USAGE] is JsonObject }
}

// The two fields of a chat request the relay reads, and the two of a stream's frame.
private const val STREAM_FLAG = "stream"
private const val STREAM_OPTIONS = "stream_options"
private const val FRAME_USAGE = "usage"
internal const val FRAME_CHOICES = "choices"

// An SSE event's data line, and the data that ends an OpenAI chat stream.
private const val DATA_FIELD = "data:"
internal const val CHAT_DONE = "[DONE]"
