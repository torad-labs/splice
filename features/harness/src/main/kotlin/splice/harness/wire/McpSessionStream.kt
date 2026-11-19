// NEW: the harness (docs/specs/harness.md, decision 13). A session's own stream on a server at a URL (MCP's GET), heard
// beside each request's answer. The daemon's MCP host sends a call's progress there, and answers the call's POST,
// headers included, only once the tool is done, so the stream is what carries the call's steps while it works.
package splice.harness.wire

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import java.io.InputStream

internal class McpSessionStream(private val replies: McpReplies) {
    /** What the server sends on the stream, as it comes. */
    private val pushed = Channel<JsonObject>(Channel.UNLIMITED)

    /** The stream's body once it is open, closed with the wire. */
    @Volatile private var body: InputStream? = null

    /** False once the stream has ended, or the server keeps none. */
    @Volatile private var pushing = true

    /** The stream opened as [body], or null when the server keeps none. */
    fun opened(body: InputStream?) {
        if (body == null) {
            pushing = false
            return
        }
        this.body = body
        replies.events(body, pushed)
    }

    /** The next message the server sends: on the stream, or in [answer], a request's answer. What the stream already
     *  holds comes first, so a line sent there before the answer is heard before it. Null once the answer has ended. */
    suspend fun next(answer: Channel<JsonObject>): JsonObject? = select {
        if (pushing) pushed.onReceiveCatching { it.getOrNull() ?: ended(answer) }
        answer.onReceiveCatching { it.getOrNull() }
    }

    /** The stream ended: the answer alone is heard from now on. */
    private suspend fun ended(answer: Channel<JsonObject>): JsonObject? {
        pushing = false
        return next(answer)
    }

    fun close() {
        body?.close()
    }
}
