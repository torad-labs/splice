// NEW: the harness (docs/specs/harness.md, decision 13). What a server at a URL sends back, read on a thread of its own
// into a channel of messages: an answer that is one JSON object, or a stream of events, whether the answer to a request
// or the session's own stream.
package splice.harness.wire

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import splice.core.parse.lenientJson
import splice.core.util.JsonScalars
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

internal class McpReplies {
    /** Reads [body], one JSON object, into [into], which it closes after; an empty one (a 202) has no message. */
    fun whole(body: InputStream, into: Channel<JsonObject>) = reading {
        try {
            val text = body.readAllBytes().toString(StandardCharsets.UTF_8).trim()
            JsonScalars.objectOrNull(lenientJson, text)?.let { into.trySend(it) }
        } catch (_: IOException) {
            // an answer that broke off has no message
        } finally {
            into.close()
        }
    }

    /** Reads [body], a stream of events, into [into], which it closes once the stream ends: each event's data lines,
     *  joined, are one message. */
    fun events(body: InputStream, into: Channel<JsonObject>) = reading {
        try {
            val data = StringBuilder()
            body.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                if (line.startsWith(SSE_DATA_PREFIX)) {
                    data.append(line.removePrefix(SSE_DATA_PREFIX).trimStart()).append('\n')
                } else if (line.isEmpty()) {
                    JsonScalars.objectOrNull(lenientJson, data.toString().trim())?.let { into.trySend(it) }
                    data.setLength(0)
                }
            }
        } catch (_: IOException) {
            // a stream that broke off ends with what it carried
        } finally {
            into.close()
        }
    }

    /** Runs [read] on a thread of its own: it blocks on the server's bytes until they end. */
    private fun reading(read: Runnable) {
        Executors.defaultThreadFactory().newThread(read).apply {
            isDaemon = true
            name = "harness-mcp-events"
        }.start()
    }
}

private const val SSE_DATA_PREFIX = "data:"
