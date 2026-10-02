package splice.head.transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A real loopback Responses stream with independently gated statements and response terminal. */
internal class StatementGatewayUpstream {
    val next = CountDownLatch(1)
    val terminal = CountDownLatch(1)
    val posts = AtomicInteger()
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/responses", ::respond)
        server.start()
    }

    private fun respond(exchange: HttpExchange) {
        posts.incrementAndGet()
        exchange.requestBody.use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { output ->
            event(
                output,
                """{"type":"response.output_item.added","output_index":0,"item":{
                    "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec","input":""}}""",
            )
            event(
                output,
                """{"type":"response.custom_tool_call_input.delta","output_index":0,
                    "delta":"await tools.Read({});\n"}""",
            )
            next.await()
            event(
                output,
                """{"type":"response.custom_tool_call_input.delta","output_index":0,
                    "delta":"await tools.Edit({});\n"}""",
            )
            terminal.await()
            event(
                output,
                """{"type":"response.completed","response":{"id":"terminal-response","status":"completed",
                    "usage":{"input_tokens":100,"output_tokens":7},"output":[{
                    "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec",
                    "input":"await tools.Read({});\nawait tools.Edit({});\n"}]}}""",
            )
        }
    }

    private fun event(output: java.io.OutputStream, json: String) {
        output.write(("data: " + json.lineSequence().joinToString("") + "\n\n").toByteArray())
        output.flush()
    }

    fun close() {
        next.countDown()
        terminal.countDown()
        server.stop(0)
        pool.shutdownNow()
    }
}

/** A source-port peer: never executes a tool, and exposes when a result reached its held cell. */
internal class StatementGatewayRuntime : CodeModeRuntime {
    val delivered = CompletableDeferred<List<CodeModeResult>>()
    val starts = AtomicInteger()

    override suspend fun start(source: String, tools: Set<String>, descriptions: Map<String, String>): CodeModeCell =
        error("a live round must use the source port")

    override suspend fun startStreaming(
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell {
        starts.incrementAndGet()
        return object : CodeModeCell {
            private var sequence = 0

            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                if (results.isNotEmpty()) delivered.complete(results)
                return when (val part = source.read()) {
                    is CodeModeSourcePart.Delta -> CodeModeStep.Calls(
                        listOf(
                            CodeModeCall(
                                "runtime-${sequence++}",
                                if (part.text.contains("Read")) "Read" else "Edit",
                                JsonObject(emptyMap()),
                            ),
                        ),
                    )
                    is CodeModeSourcePart.Complete -> CodeModeStep.Completed("done")
                    is CodeModeSourcePart.Failed -> error(part.error)
                }
            }

            override fun close() = Unit
        }
    }

    override fun close() = Unit
}
