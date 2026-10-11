// Loopback-only chat upstream with synthetic usage and captured requests.
package splice.app.roster

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

internal class RosterUpstream : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<String>()
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.createContext("/v1/chat/completions") { exchange ->
            requests.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val frames = listOf(
                """{"id":"synthetic-answer","choices":[{"index":0,""" +
                    """"delta":{"content":"synthetic answer"},"finish_reason":null}]}""",
                """{"id":"synthetic-answer","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":1000000,"completion_tokens":1000000}}""",
                "[DONE]",
            )
            exchange.responseBody.use { out ->
                frames.forEach { out.write("data: $it\n\n".toByteArray()) }
            }
            exchange.close()
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
