// The oldest splice a laptop may run against the server (docs/specs/server-mode.md, F9, the server's half):
// `[server.client] minimum`, as a team member's launches meet it with the version their splice names in
// `x-splice-client`. Over real HTTP on loopback, in front of a stand-in head that does what a head does with a turn the
// listener hands on with its notice (HeadServerLocalAnswerTest pins the real head's answer): answers it with that text,
// as one JSON message or as a Messages stream, and sends nothing upstream. Every other turn it counts as reaching the
// upstream.
package splice.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.client.SPLICE_CLIENT_HEADER
import splice.core.topology.ServerClient
import splice.core.turn.SERVED_NOTICE_HEADER
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

internal class ServerClientVersionTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val rig = ServerRig()

    /** How many turns reached the upstream: every one the listener handed on without a notice. */
    private val upstream = AtomicInteger()

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { exchange ->
            val streamed = exchange.requestBody.use { it.readBytes() }.decodeToString().contains("\"stream\":true")
            val notice = exchange.requestHeaders.getFirst(SERVED_NOTICE_HEADER)
            if (notice == null) upstream.incrementAndGet()
            answer(exchange, notice ?: "from the box", streamed)
        }
        start()
    }

    private lateinit var key: String

    @BeforeEach
    fun serve() {
        rig.open(state, listOf(StandInHead(head.address.port)), rig.config.copy(client = ServerClient(MINIMUM)))
        key = rig.keyFor("ann@team.example")
    }

    @AfterEach
    fun stop() {
        rig.close()
        head.stop(0)
    }

    /** A Messages turn from a laptop whose splice names [version], or none when it is null. */
    private fun turn(version: String?, stream: Boolean = false): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/claude-team/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .apply { version?.let { header(SPLICE_CLIENT_HEADER, it) } }
            .POST(HttpRequest.BodyPublishers.ofString(TURN_BODY.replace("\"stream\":false", "\"stream\":$stream")))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** [response] is a 200 telling the laptop on [version] to run `splice upgrade` to reach the minimum, and the turn
     *  reached no upstream. */
    private fun assertToldToUpgrade(version: String, response: HttpResponse<String>, text: String, before: Int) {
        assertEquals(200, response.statusCode(), response.body())
        assertTrue("splice $version" in text, text)
        assertTrue(MINIMUM in text, text)
        assertTrue("splice upgrade" in text, text)
        assertEquals(before, upstream.get(), "an older laptop's turn reached the upstream")
    }

    @Test
    fun `an older laptop, by number, is told in the conversation to upgrade and nothing goes upstream`() {
        for (older in listOf("0.9.0", "0.9.12", "0.2.14")) {
            val before = upstream.get()
            val response = turn(older)
            val message = Json.parseToJsonElement(response.body()).jsonObject
            assertEquals("assistant", message.getValue("role").jsonPrimitive.content)
            val text = message.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            assertToldToUpgrade(older, response, text, before)
        }
    }

    @Test
    fun `streamed, an older laptop gets the same notice as a Messages stream`() {
        val before = upstream.get()
        val response = turn("0.9.0", stream = true)
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"))
        val events = response.body().lines().filter { it.startsWith("event: ") }.map { it.removePrefix("event: ") }
        assertEquals("message_start", events.first(), response.body())
        assertEquals("message_stop", events.last(), response.body())
        val text = response.body().lines().filter { it.startsWith("data: ") && "text_delta" in it }
            .joinToString("") { line ->
                val data = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                data.getValue("delta").jsonObject.getValue("text").jsonPrimitive.content
            }
        assertToldToUpgrade("0.9.0", response, text, before)
    }

    @Test
    fun `a laptop at the minimum or past it is served, streamed or not`() {
        for (version in listOf(MINIMUM, "0.10.1", "0.11.0", "1.0.0")) {
            for (stream in listOf(false, true)) {
                val before = upstream.get()
                val response = turn(version, stream)
                assertEquals(200, response.statusCode(), response.body())
                assertFalse("splice upgrade" in response.body(), "$version was told to upgrade: ${response.body()}")
                assertEquals(before + 1, upstream.get(), "$version's turn did not reach the upstream")
            }
        }
    }

    @Test
    fun `a request that names no version, as a script's does, is served`() {
        val before = upstream.get()
        val response = turn(version = null)
        assertEquals(200, response.statusCode(), response.body())
        assertTrue("from the box" in response.body(), response.body())
        assertEquals(before + 1, upstream.get())
    }

    /** Answers [exchange] with [text] as a head answers a turn: one JSON message, or a Messages stream when
     *  [streamed]. */
    private fun answer(exchange: HttpExchange, text: String, streamed: Boolean) {
        val quoted = JsonPrimitive(text).toString()
        val block = """{"type":"text","text":$quoted}"""
        val delta = """{"type":"text_delta","text":$quoted}"""
        val body = if (streamed) {
            listOf(
                "message_start" to """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
                "content_block_delta" to """{"type":"content_block_delta","delta":$delta}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
        } else {
            """{"type":"message","role":"assistant","content":[$block],"stop_reason":"end_turn"}"""
        }.toByteArray()
        exchange.responseHeaders.add("Content-Type", if (streamed) "text/event-stream" else "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}

// Against 0.10.0, 0.9.0 is older only by number: as text it sorts after.
private const val MINIMUM = "0.10.0"

private const val TURN_BODY =
    """{"model":"team-model","max_tokens":8,"stream":false,"messages":[{"role":"user","content":"hi"}]}"""
