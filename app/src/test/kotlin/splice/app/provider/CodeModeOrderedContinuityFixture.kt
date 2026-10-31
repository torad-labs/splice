package splice.app.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import splice.codemode.WORKER_START_TIMEOUT_MS
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.headDeps
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeWiring
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import splice.codemode.LegacyCodeModeRuntime as JvmCodeModeRuntime
import splice.codemode.LegacyHostLaunch as HostLaunch

internal object OrderedContinuityItems {
    const val MODEL = "gpt-5.6-sol"
    const val PREFACE_A = "source-before-middle"
    const val PREFACE_B = "source-after-middle"
    const val PREFACE = PREFACE_A + PREFACE_B
    const val SOURCE = "console.log(await tools.Read({}));"
    val firstReasoning = reasoning("source-before")
    val lastReasoning = reasoning("source-after")
    val preface = message("preface-item", PREFACE_A)
    val afterPreface = message("after-preface-item", PREFACE_B)
    val exec = buildJsonObject {
        put("type", "custom_tool_call")
        put("id", "exec-item")
        put("call_id", "ordered-exec")
        put("name", "exec")
        put("input", SOURCE)
    }

    private fun reasoning(id: String): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", id)
        put("encrypted_content", "synthetic-$id")
        put("summary", JsonArray(emptyList()))
    }

    fun message(id: String, text: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("id", id)
        put("role", "assistant")
        put("phase", "commentary")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
    }
}

/** No fabricated outcomes or carry fields: the production Responses reader consumes this ordered SSE stream. */
internal class OrderedContinuityUpstream {
    val inputs = ConcurrentLinkedQueue<JsonArray>()
    private val terminal = CountDownLatch(1)
    private val sourceSent = AtomicBoolean()
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"
    val terminalReleased: Boolean get() = terminal.count == 0L

    init {
        server.executor = pool
        server.createContext("/responses", ::respond)
        server.start()
    }

    private fun respond(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }
        val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
        inputs += request.getValue("input").jsonArray
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { output ->
            if (sourceSent.compareAndSet(false, true)) source(output) else answer(output)
        }
        exchange.close()
    }

    private fun source(output: OutputStream) {
        added(output, 0, OrderedContinuityItems.firstReasoning)
        done(output, 0, OrderedContinuityItems.firstReasoning)
        prose(output, 1, OrderedContinuityItems.preface, OrderedContinuityItems.PREFACE_A)
        added(output, 2, OrderedContinuityItems.lastReasoning)
        done(output, 2, OrderedContinuityItems.lastReasoning)
        prose(output, 3, OrderedContinuityItems.afterPreface, OrderedContinuityItems.PREFACE_B)
        added(output, 4, JsonObject(OrderedContinuityItems.exec + ("input" to JsonPrimitive(""))))
        done(output, 4, OrderedContinuityItems.exec)
        check(terminal.await(WORKER_START_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)) { "terminal was not released" }
        completed(
            output,
            JsonArray(
                listOf(
                    OrderedContinuityItems.firstReasoning,
                    OrderedContinuityItems.preface,
                    OrderedContinuityItems.lastReasoning,
                    OrderedContinuityItems.afterPreface,
                    OrderedContinuityItems.exec,
                ),
            ),
        )
    }

    private fun prose(output: OutputStream, index: Int, item: JsonObject, text: String) {
        added(output, index, item)
        event(
            output,
            buildJsonObject {
                put("type", "response.output_text.delta")
                put("output_index", index)
                put("content_index", 0)
                put("item_id", item.getValue("id"))
                put("delta", text)
            },
        )
        done(output, index, item)
    }

    private fun answer(output: OutputStream) {
        val item = OrderedContinuityItems.message("answer-item", "finished")
        added(output, 0, item)
        event(
            output,
            buildJsonObject {
                put("type", "response.output_text.delta")
                put("output_index", 0)
                put("content_index", 0)
                put("item_id", "answer-item")
                put("delta", "finished")
            },
        )
        done(output, 0, item)
        completed(output, JsonArray(listOf(item)))
    }

    private fun added(output: OutputStream, index: Int, item: JsonObject) = event(
        output,
        buildJsonObject {
            put("type", "response.output_item.added")
            put("output_index", index)
            put("item", item)
        },
    )

    private fun done(output: OutputStream, index: Int, item: JsonObject) = event(
        output,
        buildJsonObject {
            put("type", "response.output_item.done")
            put("output_index", index)
            put("item", item)
        },
    )

    private fun completed(output: OutputStream, items: JsonArray) = event(
        output,
        buildJsonObject {
            put("type", "response.completed")
            put(
                "response",
                buildJsonObject {
                    put("id", "ordered-response-${inputs.size}")
                    put("status", "completed")
                    put("output", items)
                    put("usage", buildJsonObject { put("input_tokens", 11); put("output_tokens", 3) })
                },
            )
        },
    )

    private fun event(output: OutputStream, value: JsonObject) {
        output.write("data: $value\n\n".toByteArray())
        output.flush()
    }

    fun releaseTerminal() = terminal.countDown()

    fun close() {
        releaseTerminal()
        server.stop(0)
        pool.shutdownNow()
    }
}

/** Only the peer and tool result are synthetic; request building, caching, execution and disk restore are real. */
internal class OrderedContinuityGateway(private val tmp: Path, private val upstream: OrderedContinuityUpstream) {
    val clientContent = mutableListOf<JsonArray>()
    private val client = HttpClient(CIO) { engine { requestTimeout = 60_000L } }
    private var generation = 0
    private var runtime = runtime()
    private var head = head()
    private val opening = message("user", JsonPrimitive("start"))

    suspend fun start() = head.start()

    suspend fun first(): JsonArray = post(listOf(opening))

    suspend fun finish(callback: JsonArray): JsonArray = post(transcript(callback, echo = true))

    suspend fun next(callback: JsonArray, answer: JsonArray): JsonArray = post(
        transcript(callback, echo = false) + message("assistant", answer) + message("user", JsonPrimitive("next")),
    )

    suspend fun restart() {
        head.stop()
        runtime.close()
        generation++
        runtime = runtime()
        head = head()
        head.start()
    }

    suspend fun close() {
        head.stop()
        runtime.close()
        client.close()
    }

    private fun runtime(): JvmCodeModeRuntime = JvmCodeModeRuntime(
        launch = HostLaunch(classpath = checkNotNull(System.getProperty("codeMode.testClasspath"))),
    )

    private fun head(): HeadServer {
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            ),
        )
        val provider = CodexProvider(
            tuning = ProviderTuning(
                name = ProviderName("codex", "ordered-continuity"),
                catalog = ModelCatalog(
                    "claude-codex--",
                    listOf(ModelEntry(OrderedContinuityItems.MODEL, "Ordered", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = OrderedContinuityItems.MODEL,
                auth = object : RefreshableAuthProvider {
                    override suspend fun credentials(): Credentials = Credentials.Bearer("test-token")
                    override suspend fun refresh(): Credentials = credentials()
                    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
                },
                locations = ProviderLocations(upstream.url, tmp.resolve("reasoning-$generation")),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 60.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.OFF, true, "high", "detailed"),
            codeMode = CodexCodeModeWiring(bridge, listOf(OrderedContinuityItems.MODEL)),
        )
        return HeadServer(provider, 0, headDeps(tmp.resolve("head-$generation")))
    }

    private fun transcript(callback: JsonArray, echo: Boolean): List<JsonObject> {
        val calls = callback.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }
        val id = calls.single().jsonObject.getValue("id").jsonPrimitive.content
        val result = buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", id)
            put("content", "ordered-result")
        }
        return listOf(
            opening,
            message("assistant", if (echo) callback else JsonArray(calls)),
            message("user", JsonArray(listOf(result))),
        )
    }

    private fun message(role: String, content: JsonElement): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private suspend fun post(messages: List<JsonObject>): JsonArray {
        val request = buildJsonObject {
            put("model", "claude-codex--${OrderedContinuityItems.MODEL}")
            put("stream", false)
            put("max_tokens", 256)
            put("messages", JsonArray(messages))
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "Read")
                            put("description", "Read a synthetic fixture")
                            put("input_schema", buildJsonObject { put("type", "object") })
                        },
                    )
                },
            )
        }
        val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            bearerAuth("test-inference-token")
            headers.append("x-claude-code-session-id", "ordered-source-session")
            setBody(request.toString())
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        return Json.parseToJsonElement(body).jsonObject.getValue("content").jsonArray.also(clientContent::add)
    }
}
