package splice.app.provider.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Only supplies source text; all resulting tool values and output come through the production native guest. */
internal class CapturedMcpUpstream {
    val bodies = CopyOnWriteArrayList<JsonObject>()
    private val next = AtomicReference<String?>()
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/responses", ::respond)
        server.start()
    }

    fun source(source: String) {
        check(next.compareAndSet(null, source)) { "a source is already queued" }
    }

    private fun respond(exchange: HttpExchange) {
        bodies += Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
        val events = next.getAndSet(null)?.let(::execute) ?: answer()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { output -> events.forEach { output.write("data: $it\n\n".toByteArray()) } }
    }

    private fun execute(source: String): List<JsonObject> {
        val id = UUID.randomUUID().toString()
        fun call(input: String) = buildJsonObject {
            put("type", "custom_tool_call"); put("id", "item-$id"); put("call_id", "call-$id")
            put("name", "exec"); put("input", input)
        }
        return listOf(
            buildJsonObject {
                put(
                    "type",
                    "response.output_item.added",
                ); put("output_index", 0); put("item", call(""))
            },
            buildJsonObject {
                put("type", "response.custom_tool_call_input.delta"); put("output_index", 0); put("delta", source)
            },
            completed(JsonArray(listOf(call(source)))),
        )
    }

    private fun answer(): List<JsonObject> {
        val item = buildJsonObject { put("type", "message"); put("id", "answer-${UUID.randomUUID()}") }
        return listOf(
            buildJsonObject { put("type", "response.output_item.added"); put("output_index", 0); put("item", item) },
            buildJsonObject { put("type", "response.output_text.delta"); put("output_index", 0); put("delta", "done") },
            buildJsonObject { put("type", "response.output_item.done"); put("output_index", 0); put("item", item) },
            completed(JsonArray(emptyList())),
        )
    }

    private fun completed(output: JsonArray): JsonObject = buildJsonObject {
        put("type", "response.completed")
        put(
            "response",
            buildJsonObject {
                put("id", "response-${UUID.randomUUID()}"); put("status", "completed"); put("output", output)
                put("usage", buildJsonObject { put("input_tokens", 100); put("output_tokens", 7) })
            },
        )
    }

    fun observation(tag: String): JsonObject = bodies.asReversed().asSequence()
        .flatMap { (it["input"] as? JsonArray).orEmpty().asSequence() }
        .filterIsInstance<JsonObject>().filter { it["type"] == JsonPrimitive("custom_tool_call_output") }
        .flatMap { strings(it.getValue("output")).asSequence() }.flatMap { it.lineSequence() }
        .mapNotNull { line -> runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() }
        .firstOrNull { it["tag"] == JsonPrimitive(tag) }
        ?: error("the model did not receive native output for $tag: ${bodies.joinToString().takeLast(12_000)}")

    private fun strings(value: JsonElement): List<String> = when (value) {
        is JsonPrimitive -> if (value.isString) listOf(value.content) else emptyList()
        is JsonObject -> value.values.flatMap(::strings)
        is JsonArray -> value.flatMap(::strings)
    }

    fun ordinaryOutput(id: String): JsonElement? = bodies.asReversed().asSequence()
        .flatMap { (it["input"] as? JsonArray).orEmpty().asSequence() }.filterIsInstance<JsonObject>()
        .firstOrNull { it["type"] == JsonPrimitive("function_call_output") && it["call_id"] == JsonPrimitive(id) }
        ?.get("output")

    fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}
