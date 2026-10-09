// NEW: V4-456 — a GPT code-mode script reaches the client's wire while the model writes it, not when it ends, before a
// dispatched tool call and after the client's result resumes the same script. Measured at the client boundary: a
// scripted upstream sends one script delta at a time and waits, with a deadline, for the client to read a thinking
// delta from the head's response before it sends the next. A head that holds a delta lets the
// deadline pass, so that delta is recorded as late. The operator reported the burst back after Oct 2, when afb7a1f87
// and c55803df6 put the live script on the wire as a signed notice block.
package splice.app.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.codemode.WORKER_START_TIMEOUT_MS
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.stream.FoldConfig
import splice.head.HeadServer
import splice.head.headDeps
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeWiring
import splice.provider.codex.CodexProvider
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.WsRound
import splice.upstream.WsRoundAbort
import splice.upstream.WsRoundRunner
import splice.upstream.retry.InflightGate
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

// why: the scenario boots a real code-mode worker JVM and then streams a short script.
private const val LIVE_TEST_SECONDS = 90L

// why: the product allows the worker WORKER_START_TIMEOUT_MS to start; the bound adds room for the rest of the turn.
private const val WORKER_BOUND_MS = WORKER_START_TIMEOUT_MS + 5_000L

// why: longer than a pacer tick plus scheduling jitter, far shorter than the seconds a held script lasted on Oct 2.
private const val LIVE_BOUND_MS = 400L

// why: of the script chunks after the first, the one at this index dispatches the tool call and is not timed.
private const val DISPATCH_CHUNK = 3
private const val TIMED_DELTAS = 6
private const val NANOS_PER_MS = 1_000_000L
private const val LATE = -1L
private const val RESUME_BOUND_MS = 3_000L
private const val STALL_PROBE_MS = 1_500L
private const val STALL_FRAMES = 5
private const val LOG_CHARS = 7000
private const val STACK_CHARS = 4000
private const val SEEN_CHARS = 900

class CodeModeLiveScriptTest {
    private val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @Test
    @Timeout(LIVE_TEST_SECONDS)
    fun `each script delta of a streaming exec reaches the client within a bound of leaving the upstream`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = TimedScriptUpstream()
        val logs = ConcurrentLinkedQueue<String>()
        val runtime = StatementGatewayRuntime()
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
                log = { line -> logs += line },
            ),
        )
        val head = HeadServer(
            provider(upstream.url, bridge) { line -> logs += line },
            0,
            headDeps(tmp, log = { line -> logs += line }, gate = InflightGate({ 4 }, maxQueued = { 4 })),
        )
        val client = HttpClient(CIO) { engine { requestTimeout = TimeUnit.SECONDS.toMillis(LIVE_TEST_SECONDS) } }
        try {
            head.start()
            val booted = launch {
                withTimeout(WORKER_BOUND_MS) { runtime.started.await() }
                upstream.workerReady.countDown()
            }
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val assistant = checkNotNull(reader(client, url, REQUEST, upstream.beforeDispatch) { }) {
                "the script's tool call never reached the client"
            }
            reader(client, url, resumedRequest(assistant), upstream.afterResume) { upstream.resumed.countDown() }
            booted.join()
            upstream.scriptDone.await(WORKER_BOUND_MS, TimeUnit.MILLISECONDS)
            val lateness = upstream.timedLateness.toList()
            assertEquals(TIMED_DELTAS, lateness.size, diagnosis(upstream, runtime, lateness, logs))
            assertTrue(lateness.all { it != LATE }, diagnosis(upstream, runtime, lateness, logs))
        } finally {
            upstream.close()
            head.stop()
            runtime.close()
            client.close()
        }
    }

    /** A folding code-mode round whose websocket draft fails before any client frame is answered again over SSE on
     *  the same code-mode sink. The draft goes whole, its blocks with its text, so the client reads only the SSE
     *  answer and the turn ends cleanly. While the draft's block outlived the discard, the answer's cleanup closed it
     *  on the fold buffer, which failed the turn at flush ("Key -1 is missing"). */
    @Test
    @Timeout(LIVE_TEST_SECONDS)
    fun `a failed websocket draft on a folding code-mode turn leaves the client only the SSE answer`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = SseAnswerUpstream()
        val runtime = StatementGatewayRuntime()
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
                log = {},
            ),
        )
        val codex = provider(upstream.url, bridge, FoldConfig(models = setOf("gpt-5.6-sol"))) {}
        val folding = object : Provider by codex {
            override val wsRunner: WsRoundRunner = DraftThenFailure()
        }
        val head = HeadServer(folding, 0, headDeps(tmp, log = {}))
        val client = HttpClient(CIO)
        try {
            head.start()
            val answer = client.post("http://127.0.0.1:${head.port}/v1/messages") {
                bearerAuth("test-inference-token")
                headers.append("x-claude-code-session-id", "fold-draft-session")
                setBody(REQUEST)
            }.bodyAsText()
            assertTrue(answer.contains("SSE ANSWER"), answer)
            assertFalse(answer.contains("WS DRAFT"), answer)
            assertTrue(answer.contains("message_stop") && !answer.contains("event: error"), answer)
        } finally {
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    /** What the failure says about the run: each timed delta's lateness, how the scripted upstream ended, whether the
     *  script finished, and what both sides saw. */
    private suspend fun diagnosis(
        upstream: TimedScriptUpstream,
        runtime: StatementGatewayRuntime,
        lateness: List<Long>,
        logs: Collection<String>,
    ): String {
        val finished = runtime.completed.takeIf { it.isCompleted }?.await()
        return "each script delta must reach the client within $LIVE_BOUND_MS ms of leaving the upstream; " +
            "${lateness.size} timed deltas (ms, -1 late): $lateness; ${upstream.scriptEnd}; " +
            "script finished: $finished; calls: ${runtime.calls.size}; " +
            "upstream trail: ${upstream.trail.joinToString(" ")}; " +
            "stalled threads: ${upstream.stack.take(STACK_CHARS)}; " +
            "client saw: ${seen.joinToString(" || ").take(SEEN_CHARS)}; " +
            "log: ${logs.joinToString(" | ").take(LOG_CHARS)}"
    }

    /** Reads one response as it streams, releasing a permit per thinking delta for the upstream. Answers the assistant
     *  message the client would replay, as its tool_use blocks end it, or null when the response ended without a call.
     *  [onStart] runs once the response is open. */
    private suspend fun reader(
        client: HttpClient,
        url: String,
        request: String,
        sawThinking: Semaphore,
        onStart: () -> Unit,
    ): JsonArray? {
        val replay = ReplayedBlocks()
        client.preparePost(url) {
            bearerAuth("test-inference-token")
            headers.append("x-claude-code-session-id", "live-script-session")
            setBody(request)
        }.execute { response ->
            onStart()
            val channel = response.bodyAsChannel()
            var line = channel.readLine()
            while (line != null) {
                if (line.startsWith("data: ")) {
                    val event = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                    if (isThinkingDelta(event)) sawThinking.release()
                    replay.take(event)
                    if (!isThinkingDelta(event)) seen += "${request.length % 1000}:" + event.toString().take(60)
                }
                line = channel.readLine()
            }
        }
        return replay.blocks().takeIf { blocks ->
            blocks.any { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }
        }
    }

    private fun resumedRequest(assistant: JsonArray): String {
        val callId = assistant.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content == "tool_use" }
            .getValue("id").jsonPrimitive.content
        return """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,"messages":[
            {"role":"user","content":"go"},
            {"role":"assistant","content":$assistant},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"$callId","content":"read-result"}]}],
            "tools":[{"name":"Read","description":"Read a synthetic fixture","input_schema":{"type":"object"}}]}"""
    }

    private fun isThinkingDelta(event: JsonObject): Boolean =
        event["type"]?.jsonPrimitive?.content == "content_block_delta" &&
            event.getValue("delta").jsonObject["type"]?.jsonPrimitive?.content == "thinking_delta"

    private fun provider(
        url: String,
        bridge: CodexCodeModeBridge,
        fold: FoldConfig? = null,
        log: LogSink,
    ): CodexProvider = CodexProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = "codex", label = "live-script-test"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Stream", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = object : RefreshableAuthProvider {
                override suspend fun credentials(): Credentials = Credentials.Bearer("test-token")
                override suspend fun refresh(): Credentials = credentials()
                override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
            },
            locations = ProviderLocations(baseUrl = url),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, null, null),
        foldConfig = fold,
        codeMode = CodexCodeModeWiring(bridge = bridge, models = listOf("gpt-5.6-sol")),
        log = log,
    )
}

/** Folds a response's stream events back into the content blocks a client replays in its next request. */
private class ReplayedBlocks {
    private val blocks = sortedMapOf<Int, MutableMap<String, JsonElement>>()
    private val partial = mutableMapOf<Int, StringBuilder>()

    fun take(event: JsonObject) {
        val index = event["index"]?.jsonPrimitive?.content?.toInt() ?: return
        when (event["type"]?.jsonPrimitive?.content) {
            "content_block_start" -> blocks[index] = event.getValue("content_block").jsonObject.toMutableMap()
            "content_block_delta" -> delta(index, event.getValue("delta").jsonObject)
        }
    }

    private fun delta(index: Int, delta: JsonObject) {
        val block = blocks[index] ?: return
        when (delta["type"]?.jsonPrimitive?.content) {
            "thinking_delta" -> {
                val so = block["thinking"]?.jsonPrimitive?.content.orEmpty()
                block["thinking"] = JsonPrimitive(so + delta.getValue("thinking").jsonPrimitive.content)
            }
            "signature_delta" -> block["signature"] = delta.getValue("signature")
            "input_json_delta" -> partial.getOrPut(index) { StringBuilder() }
                .append(delta.getValue("partial_json").jsonPrimitive.content)
        }
    }

    fun blocks(): JsonArray = JsonArray(
        blocks.map { (index, block) ->
            partial[index]?.let { block["input"] = Json.parseToJsonElement(it.toString().ifEmpty { "{}" }) }
            JsonObject(block)
        },
    )
}

private const val REQUEST =
    """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
        "messages":[{"role":"user","content":"go"}],
        "tools":[{"name":"Read","description":"Read a synthetic fixture","input_schema":{"type":"object"}}]}"""

/** An upstream whose exec script streams one delta at a time. The first leaves at once, so the code-mode worker can
 *  boot. Once the test says the worker is ready, [TIMED_DELTAS] more leave, each only after the client has read a
 *  thinking delta for the one before it, or after [LIVE_BOUND_MS] without one. Then the round completes. */
private class TimedScriptUpstream {
    val workerReady = CountDownLatch(1)

    /** Released when the client's result request is open: the script continues in that response. */
    val resumed = CountDownLatch(1)

    /** Counted down when the scripted response has left the upstream, written or not. */
    val scriptDone = CountDownLatch(1)

    @Volatile var scriptEnd: String = "script completed"

    /** One permit per thinking delta the client read from the first response, and from the resumed one. */
    val beforeDispatch = Semaphore(0)
    val afterResume = Semaphore(0)

    /** For each timed delta, ms from its flush to the client's next thinking delta; null when none came in time. */
    val timedLateness = ConcurrentLinkedQueue<Long>()
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var posts = 0
    private val startedNanos = System.nanoTime()
    val trail = ConcurrentLinkedQueue<String>()

    @Volatile var stack: String = ""

    /** Where the head's threads stand when the resumed response has not opened after [STALL_PROBE_MS]. */
    private fun stallDump() {
        if (resumed.await(STALL_PROBE_MS, TimeUnit.MILLISECONDS)) return
        stack = Thread.getAllStackTraces().filter { (_, frames) -> frames.any { it.className.startsWith("splice.") } }
            .values.joinToString(" ## ") { frames ->
                frames.filter { it.className.startsWith("splice.") }.take(STALL_FRAMES)
                    .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            }
    }

    fun elapsed(): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MS

    fun mark(what: String) {
        trail += "$what@${(System.nanoTime() - startedNanos) / NANOS_PER_MS}"
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/responses", ::respond)
        server.start()
    }

    private fun respond(exchange: HttpExchange) {
        val attempt = synchronized(this) { ++posts }
        exchange.requestBody.readAllBytes()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        try {
            exchange.responseBody.use { output -> if (attempt == 1) script(output) else answer(output) }
        } catch (failure: java.io.IOException) {
            if (attempt == 1) {
                scriptEnd = "upstream write failed after ${timedLateness.size} timed deltas at ${elapsed()} ms: " +
                    "${failure.message}"
            }
        } finally {
            if (attempt == 1) scriptDone.countDown()
        }
    }

    private fun script(output: java.io.OutputStream) {
        event(
            output,
            """{"type":"response.output_item.added","output_index":0,"item":{
                "type":"custom_tool_call","id":"script-item","call_id":"script-call","name":"exec","input":""}}""",
        )
        // Certification needs a closed token after the statement that dispatches, so that chunk ends inside the next.
        val chunks = listOf(
            "text ('piece 0');\n",
            "text ('piece 1');\n",
            "text ('piece 2');\n",
            "text ('piece 3');\n",
            "await tools.Read({});\ntext (",
            "'piece 4');\n",
            "text ('piece 5');\n",
            "text ('piece 6');\n",
        )
        delta(output, chunks.first())
        mark("first")
        workerReady.await(WORKER_BOUND_MS, TimeUnit.MILLISECONDS)
        mark("workerReady")
        chunks.drop(1).forEachIndexed { index, chunk ->
            // The client is running its tool: the rest of the script is sent once its next step has opened.
            if (index == DISPATCH_CHUNK + 1) stepOpens()
            delta(output, chunk)
            mark("c${index + 1}")
            if (index != DISPATCH_CHUNK) {
                timedLateness += awaitClient(if (index < DISPATCH_CHUNK) beforeDispatch else afterResume)
            }
        }
        event(
            output,
            """{"type":"response.completed","response":{"id":"script-response","status":"completed",
                "usage":{"input_tokens":100,"output_tokens":7},"output":[{
                "type":"custom_tool_call","id":"script-item","call_id":"script-call","name":"exec",
                "input":${JsonPrimitive(chunks.joinToString(""))}}]}}""",
        )
    }

    /** The client's next response must open within [RESUME_BOUND_MS] of the tool result. */
    private fun stepOpens() {
        pool.submit { stallDump() }
        mark(if (resumed.await(RESUME_BOUND_MS, TimeUnit.MILLISECONDS)) "resumed" else "resumeTimedOut")
    }

    private fun awaitClient(sawThinking: Semaphore): Long {
        val sentAt = System.nanoTime()
        val seen = sawThinking.tryAcquire(LIVE_BOUND_MS, TimeUnit.MILLISECONDS)
        return if (seen) (System.nanoTime() - sentAt) / NANOS_PER_MS else LATE
    }

    private fun answer(output: java.io.OutputStream) {
        event(
            output,
            """{"type":"response.output_item.added","output_index":0,"item":{
                "type":"message","id":"answer-item","role":"assistant","content":[]}}""",
        )
        event(
            output,
            """{"type":"response.completed","response":{"id":"answer-response","status":"completed",
                "usage":{"input_tokens":11,"output_tokens":3},"output":[{
                "type":"message","id":"answer-item","role":"assistant",
                "content":[{"type":"output_text","text":"done"}]}]}}""",
        )
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
        workerReady.countDown()
        resumed.countDown()
        scriptDone.countDown()
        server.stop(0)
        pool.shutdownNow()
    }
}

/** A websocket round that writes a text draft and then fails, before the fold buffer lets any of it reach the client. */
private class DraftThenFailure : WsRoundRunner {
    private var attempted = false

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound? {
        if (attempted) return null
        attempted = true
        val events = listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"draft-item"}}""",
            """{"type":"response.output_text.delta","output_index":0,"delta":"WS DRAFT"}""",
            """{"type":"response.failed","response":{"status":"failed"}}""",
        )
        return WsRound(flow { events.forEach { emit(Json.parseToJsonElement(it).jsonObject) } }, WsRoundAbort {})
    }

    override fun isFailureTerminal(event: JsonObject): Boolean =
        event["type"]?.jsonPrimitive?.content == "response.failed"

    override fun roundEnded(meta: TurnMeta, ok: Boolean) = Unit

    override fun roundBypassed(meta: TurnMeta) = Unit
}

/** The SSE upstream the head falls back to: one round that answers in text and completes. */
private class SseAnswerUpstream {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/responses") { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                listOf(
                    """{"type":"response.output_item.added","output_index":0,""" +
                        """"item":{"type":"message","id":"answer"}}""",
                    """{"type":"response.output_text.delta","output_index":0,"delta":"SSE ANSWER"}""",
                    """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"answer"}}""",
                    """{"type":"response.completed","response":{"id":"answer-response","status":"completed",""" +
                        """"output":[],"usage":{"input_tokens":11,"output_tokens":3}}}""",
                ).forEach { output.write("data: $it\n\n".toByteArray()) }
            }
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    fun close() {
        server.stop(0)
    }
}
