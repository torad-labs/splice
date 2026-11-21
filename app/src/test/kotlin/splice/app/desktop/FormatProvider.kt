// A provider of one upstream format on a loopback port, for the gateway's acceptance tests: it answers a turn in
// Anthropic Messages, OpenAI Chat or OpenAI Responses (streamed when the request asks), lists its one model, and keeps
// every request it was sent, so a test says which provider a turn reached, as which model, and under whose credential.
package splice.app.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** The three upstream formats the gateway's provider adapters speak. */
internal enum class Format { MESSAGES, CHAT, RESPONSES }

/** One request a provider was sent: its [path], every header (names lowercased) and its [body]. */
internal data class Seen(val path: String, val headers: Map<String, String>, val body: String) {
    val everything: String get() = headers.values.joinToString("\n") + "\n" + body
}

internal class FormatProvider(private val format: Format, private val model: String, private val says: String) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()

    /** The turns this provider was sent (a model list is not one). */
    val turns = CopyOnWriteArrayList<Seen>()

    /** When set, every turn is refused with this status and body. */
    @Volatile var refusing: Pair<Int, String>? = null

    val baseUrl get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.executor = pool
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().decodeToString()
        if (ex.requestMethod == "GET") return reply(ex, 200, JSON, """{"data":[{"id":"$model","object":"model"}]}""")
        val headers = ex.requestHeaders.entries.associate { (name, values) ->
            name.lowercase() to values.joinToString()
        }
        turns += Seen(ex.requestURI.path, headers, body)
        val refused = refusing
        when {
            refused != null -> reply(ex, refused.first, JSON, refused.second)
            "\"stream\":true" in body.replace(" ", "") -> reply(ex, 200, STREAM, stream())
            else -> reply(ex, 200, JSON, whole())
        }
    }

    private fun reply(ex: HttpExchange, status: Int, type: String, text: String) {
        val bytes = text.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun whole(): String = when (format) {
        Format.MESSAGES ->
            """{"id":"msg_1","type":"message","role":"assistant","model":"$model",""" +
                """"content":[{"type":"text","text":"$says"}],"stop_reason":"end_turn",""" +
                """"usage":{"input_tokens":1000,"output_tokens":2}}"""
        Format.CHAT ->
            """{"id":"c1","object":"chat.completion","model":"$model","choices":[{"index":0,""" +
                """"message":{"role":"assistant","content":"$says"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":1000,"completion_tokens":2,"total_tokens":1002}}"""
        Format.RESPONSES ->
            """{"id":"resp_1","object":"response","status":"completed","model":"$model",""" +
                """"output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",""" +
                """"content":[{"type":"output_text","text":"$says"}]}],""" +
                """"usage":{"input_tokens":1000,"output_tokens":2,"total_tokens":1002}}"""
    }

    private fun stream(): String = when (format) {
        Format.MESSAGES -> messagesStream()
        Format.CHAT -> chatStream()
        Format.RESPONSES -> responsesStream()
    }

    private fun messagesStream(): String = events(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message",""" +
            """"role":"assistant","model":"$model","content":[],"stop_reason":null,""" +
            """"usage":{"input_tokens":1000,"output_tokens":0}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,""" +
            """"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,""" +
            """"delta":{"type":"text_delta","text":"$says"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},""" +
            """"usage":{"output_tokens":2}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    private fun chatStream(): String = data(
        """{"id":"c1","object":"chat.completion.chunk","model":"$model","choices":[{"index":0,"delta":""" +
            """{"role":"assistant","content":"$says"},"finish_reason":null}]}""",
        """{"id":"c1","object":"chat.completion.chunk","model":"$model","choices":[{"index":0,"delta":{},""" +
            """"finish_reason":"stop"}]}""",
        """{"id":"c1","object":"chat.completion.chunk","model":"$model","choices":[],"usage":{"prompt_tokens":1000,""" +
            """"completion_tokens":2,"total_tokens":1002}}""",
    ) + "data: [DONE]\n\n"

    private fun responsesStream(): String = events(
        "response.created" to """{"type":"response.created","response":{"id":"resp_1","object":"response",""" +
            """"status":"in_progress","model":"$model","output":[]}}""",
        "response.output_item.added" to """{"type":"response.output_item.added","output_index":0,""" +
            """"item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress","content":[]}}""",
        "response.content_part.added" to """{"type":"response.content_part.added","item_id":"msg_1",""" +
            """"output_index":0,"content_index":0,"part":{"type":"output_text","text":""}}""",
        "response.output_text.delta" to """{"type":"response.output_text.delta","item_id":"msg_1",""" +
            """"output_index":0,"content_index":0,"delta":"$says"}""",
        "response.output_text.done" to """{"type":"response.output_text.done","item_id":"msg_1",""" +
            """"output_index":0,"content_index":0,"text":"$says"}""",
        "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,""" +
            """"item":{"type":"message","id":"msg_1","role":"assistant","status":"completed",""" +
            """"content":[{"type":"output_text","text":"$says"}]}}""",
        "response.completed" to """{"type":"response.completed","response":{"id":"resp_1",""" +
            """"object":"response","status":"completed","model":"$model","output":[{"type":"message",""" +
            """"id":"msg_1","role":"assistant","status":"completed","content":""" +
            """[{"type":"output_text","text":"$says"}]}],""" +
            """"usage":{"input_tokens":1000,"output_tokens":2,"total_tokens":1002}}}""",
    )

    private fun events(vararg frames: Pair<String, String>): String =
        frames.joinToString("") { (name, json) -> "event: $name\ndata: $json\n\n" }

    private fun data(vararg frames: String): String = frames.joinToString("") { "data: $it\n\n" }
}

private const val JSON = "application/json"
private const val STREAM = "text/event-stream"
