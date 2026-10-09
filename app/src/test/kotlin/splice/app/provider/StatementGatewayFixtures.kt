package splice.app.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import splice.codemode.JvmCodeModeRuntime
import splice.codemode.host.HostLaunch
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSealedSource
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A real loopback Responses stream with independently gated statements and response terminal. */
internal class StatementGatewayUpstream(private val batch: String? = null) {
    val next = CountDownLatch(1)
    val terminal = CountDownLatch(1)
    val posts = AtomicInteger()
    val requests = ConcurrentLinkedQueue<String>()
    private val first = statement("Read")
    private val second = statement("Edit")
    private val suffix = if (batch == null) "text ('');\n" else "text ('native batch finished');\n"
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/responses", ::respond)
        server.start()
    }

    private fun respond(exchange: HttpExchange) {
        val attempt = posts.incrementAndGet()
        requests += exchange.requestBody.bufferedReader().use { it.readText() }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { output ->
            if (attempt == 1) source(output) else completion(output)
        }
    }

    private fun source(output: java.io.OutputStream) {
        event(
            output,
            """{"type":"response.output_item.added","output_index":0,"item":{
                "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec","input":""}}""",
        )
        // Certification needs a closed following token, not merely the statement's semicolon.
        delta(output, first + second.take(6))
        next.await()
        delta(output, second.drop(6) + suffix.take(5))
        terminal.await()
        if (suffix.isNotEmpty()) delta(output, suffix.drop(5))
        event(
            output,
            """{"type":"response.completed","response":{"id":"terminal-response","status":"completed",
                "usage":{"input_tokens":100,"output_tokens":7},"output":[{
                "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec",
                "input":${JsonPrimitive(first + second + suffix)}}]}}""",
        )
    }

    private fun completion(output: java.io.OutputStream) {
        event(
            output,
            """{"type":"response.output_item.added","output_index":0,"item":{
                "type":"message","id":"answer-item","role":"assistant","content":[]}}""",
        )
        event(
            output,
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,
                "item_id":"answer-item","delta":"native batch complete"}""",
        )
        event(
            output,
            """{"type":"response.completed","response":{"id":"answer-response","status":"completed",
                "usage":{"input_tokens":11,"output_tokens":3},"output":[{
                "type":"message","id":"answer-item","role":"assistant",
                "content":[{"type":"output_text","text":"native batch complete"}]}]}}""",
        )
    }

    private fun statement(tool: String): String = if (batch == null) {
        "await tools.$tool({});\n"
    } else {
        "await Promise.$batch([tools.$tool({fixture:'first'}), tools.$tool({fixture:'second'})]);\n"
    }

    private fun delta(output: java.io.OutputStream, text: String) {
        event(
            output,
            """{"type":"response.custom_tool_call_input.delta","output_index":0,"delta":${JsonPrimitive(text)}}""",
        )
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

/** Observes the real native cell without implementing parsing, admission, execution or tool publication. */
internal class StatementGatewayRuntime(
    private val excludeBatchAdmission: Boolean = false,
    private val startupDelayMs: Long = 0L,
) : CodeModeRuntime {
    val delivered = Channel<List<CodeModeResult>>(Channel.UNLIMITED)
    val started = CompletableDeferred<Unit>()
    val completed = CompletableDeferred<CodeModeStep.Completed>()
    val starts = AtomicInteger()
    val calls = ConcurrentLinkedQueue<CodeModeCall>()
    private val runtime = JvmCodeModeRuntime(
        launch = HostLaunch(
            classpath = checkNotNull(System.getProperty("codeMode.testClasspath")),
        ),
    )

    override suspend fun start(source: String, tools: Set<String>, descriptions: Map<String, String>): CodeModeCell =
        error("a live round must use the source port")

    override suspend fun startStreaming(
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell {
        starts.incrementAndGet()
        delay(startupDelayMs)
        val cell = runtime.startStreaming(admit(source), tools, descriptions)
        started.complete(Unit)
        return object : CodeModeCell {
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                if (results.isNotEmpty()) delivered.send(results)
                return cell.advance(results).also { step ->
                    when (step) {
                        is CodeModeStep.Calls -> calls.addAll(step.calls)
                        is CodeModeStep.Completed -> completed.complete(step)
                    }
                }
            }

            override fun close() = cell.close()
        }
    }

    private fun admit(source: CodeModeSource): CodeModeSource {
        if (!excludeBatchAdmission) return source
        val sealed = checkNotNull(source as? CodeModeSealedSource)
        check("Promise" in sealed.sealedGlobals) { "The mutant must remove a real producer commitment" }
        return object : CodeModeSealedSource {
            override val sealedGlobals: Set<String> = sealed.sealedGlobals - "Promise"
            override suspend fun read(): CodeModeSourcePart = source.read()
        }
    }

    override fun close() {
        runtime.close()
        delivered.close()
    }
}
