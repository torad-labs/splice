// NEW: open-list fields and future events cross a real Anthropic head without interpreting review verdicts.
package splice.head.wire

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.ClientAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.headDeps
import splice.upstream.ProviderTuning
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class PassthroughOpenFieldsTest {
    @Test
    fun `native review fields and future events reach the streaming client`(@TempDir dir: Path) = runBlocking {
        val rig = OpenFieldsRig(dir)
        try {
            rig.head.start()
            val response = rig.turn(true)
            assertEquals(HttpStatusCode.OK, response.first)
            val frames = response.second.lineSequence().filter { it.startsWith("data: ") }
                .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
            val start = frames.first { it["type"]?.jsonPrimitive?.content == "message_start" }
            val delta = frames.first { it["type"]?.jsonPrimitive?.content == "message_delta" }
            val text = frames.first { it["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "text" }
            assertAll(
                {
                    assertEquals(
                        openEvent(0)["message"]?.jsonObject?.get("safeguard_results"),
                        start["message"]?.jsonObject?.get("safeguard_results"),
                    )
                },
                { assertEquals(openEvent(8)["safeguard_results"], delta["safeguard_results"]) },
                { assertEquals(openEvent(1)["content_block"], text["content_block"]) },
                { assertTrue(frames.any { it["type"]?.jsonPrimitive?.content == "future_review" }) },
                {
                    assertTrue(
                        frames.any { it["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "future_block" },
                    )
                },
            )
            verifyStreamingExtensions(frames, start, delta, text)
        } finally {
            rig.close()
        }
    }

    private fun verifyStreamingExtensions(
        frames: List<JsonObject>,
        start: JsonObject,
        delta: JsonObject,
        text: JsonObject,
    ) {
        val future = frames.first { it["type"]?.jsonPrimitive?.content == "future_review" }
        assertEquals(openEvent(3)["safeguard_results"], future["safeguard_results"])
        assertTrue(frames.indexOf(future) > frames.indexOf(text), "the future event stays after its block")
        assertEquals(1, frames.count { it["type"]?.jsonPrimitive?.content == "message_start" })
        assertTrue(start.containsKey("future_start"), "an opaque null is not an absent field")
        val nativeMessage = openEvent(0)["message"]!!.jsonObject
        val clientMessage = start["message"]!!.jsonObject
        assertEquals(nativeMessage["future_message"], clientMessage["future_message"])
        assertEquals(openEvent(2), frames.first { it.containsKey("future_delta") })
        assertEquals(openEvent(4), frames.first { it.containsKey("future_stop") })
        assertEquals(openEvent(3)["opaque"], future["opaque"])
        val nativeEnding = openEvent(8)
        assertEquals(
            nativeEnding["delta"]!!.jsonObject["future_stop_data"],
            delta["delta"]!!.jsonObject["future_stop_data"],
        )
        assertEquals(
            nativeEnding["usage"]!!.jsonObject["future_usage"],
            delta["usage"]!!.jsonObject["future_usage"],
        )
        assertTrue(frames.first { it["type"]?.jsonPrimitive?.content == "message_stop" }.containsKey("future_end"))
        val raw = frames.first { it["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "future_block" }
        assertEquals(
            Json.parseToJsonElement("1"),
            raw["index"],
            "source index 3 maps to the second real client block",
        )
        val rawDelta = frames.first { it["delta"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "future_payload" }
        assertEquals(openEvent(6)["delta"], rawDelta["delta"])
        assertEquals(raw["index"], rawDelta["index"])
    }

    @Test
    fun `native review and content fields survive a non streaming client request`(@TempDir dir: Path) = runBlocking {
        val rig = OpenFieldsRig(dir)
        try {
            rig.head.start()
            val response = rig.turn(false)
            assertEquals(HttpStatusCode.OK, response.first)
            val message = Json.parseToJsonElement(response.second).jsonObject
            val content = message["content"]?.jsonArray.orEmpty()
            assertAll(
                { assertEquals(openEvent(8)["safeguard_results"], message["safeguard_results"]) },
                { assertEquals(openEvent(0)["message"]?.jsonObject?.get("future_message"), message["future_message"]) },
                {
                    assertEquals(
                        openEvent(1)["content_block"]?.jsonObject?.get("safeguard_results"),
                        content.firstOrNull()?.jsonObject?.get("safeguard_results"),
                    )
                },
                { assertTrue(content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "future_block" }) },
            )
            val text = content.first().jsonObject
            assertTrue(text.containsKey("future_block_field"))
            assertEquals(openEvent(2)["future_delta"], text["future_delta"])
            assertEquals(openEvent(2)["delta"]!!.jsonObject["future_text"], text["future_text"])
            assertEquals(openEvent(4)["future_stop"], text["future_stop"])
            assertEquals(openEvent(8)["delta"]!!.jsonObject["future_stop_data"], message["future_stop_data"])
            assertEquals(
                openEvent(8)["usage"]!!.jsonObject["future_usage"],
                message["usage"]!!.jsonObject["future_usage"],
            )
            assertTrue(message.containsKey("future_end"))
            val raw = content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "future_block" }.jsonObject
            assertEquals(openEvent(6)["delta"]!!.jsonObject["payload"], raw["payload"])
        } finally {
            rig.close()
        }
    }
}

private fun openEvent(index: Int): JsonObject = Json.parseToJsonElement(OPEN_EVENTS[index]).jsonObject

private class OpenFieldsRig(directory: Path) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { request ->
            request.requestBody.use { it.readBytes() }
            request.responseHeaders.add("Content-Type", "text/event-stream")
            val bytes = OPEN_EVENTS.joinToString("\n\n", postfix = "\n\n") { event ->
                val type = Json.parseToJsonElement(event).jsonObject["type"]?.jsonPrimitive?.content
                "event: $type\ndata: $event"
            }.toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val client = HttpClient(CIO)
    val head = HeadServer(
        PassthroughProvider(
            ProviderTuning(
                key = "open",
                label = "open",
                catalog = ModelCatalog(
                    discoveryPrefix = "open--",
                    models = listOf(ModelEntry("native", "Native", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "native",
                auth = ClientAuthProvider("open"),
                baseUrl = "http://127.0.0.1:${server.address.port}",
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            PassthroughQuirks(providerTag = "open"),
        ),
        listenPort = 0,
        deps = headDeps(tmp = directory, policy = HeadDeps.HeadPolicy(forwardClientAuth = true)),
    )

    suspend fun turn(stream: Boolean): Pair<HttpStatusCode, String> {
        val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Authorization", "Bearer synthetic-open-list")
            header("Content-Type", "application/json")
            setBody(
                """{"model":"open--native","stream":$stream,"max_tokens":16,"messages":[{"role":"user","content":"synthetic"}],"safeguards":[{"type":"dangerous_tool_use"}]}""",
            )
        }
        return response.status to response.bodyAsText()
    }

    suspend fun close() {
        head.stop()
        client.close()
        server.stop(0)
    }
}

private val OPEN_EVENTS = listOf(
    """{"type":"message_start","future_start":null,"message":{"id":"native-open","type":"message","role":"assistant","model":"native","content":[],"usage":{"input_tokens":1,"output_tokens":0},"safeguard_results":[{"type":"dangerous_tool_use","status":{"type":"available","tool_uses":{}}}],"future_message":{"opaque":[false,null,3]}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"","safeguard_results":[{"source":"block"}],"future_block_field":null}}""",
    """{"type":"content_block_delta","index":0,"future_delta":"opaque","delta":{"type":"text_delta","text":"hello","future_text":{"kept":true}}}""",
    """{"type":"future_review","index":0,"safeguard_results":[{"source":"future"}],"opaque":{"index":77}}""",
    """{"type":"content_block_stop","index":0,"future_stop":false}""",
    """{"type":"content_block_start","index":3,"content_block":{"type":"future_block","payload":{"kept":[null,false]}}}""",
    """{"type":"content_block_delta","index":3,"delta":{"type":"future_payload","payload":{"kept":true}}}""",
    """{"type":"content_block_stop","index":3}""",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn","future_stop_data":{"kept":true}},"usage":{"output_tokens":1,"future_usage":{"kept":true}},"safeguard_results":[{"source":"delta"}]}""",
    """{"type":"message_stop","future_end":null}""",
)
