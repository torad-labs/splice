// The share listener since share-door-only (docs/specs/door-network.md, decision 5): a sharing machine answers its
// name at GET /, with no command of its own, and its door; every address that named one command answers 404 in the
// Anthropic error shape Claude Code prints, saying the door replaced it and the add to run on the other machine, at the
// Host the request used. Admission runs before any of it. Rewritten from the per-command listener's tests (a head not
// running or not answering, a login pinned, a relayed launch proof): that share is gone by decision, and what still
// holds, a turn of any content type refused in words or answered, never a 500, is shown against the door. Driven over
// real HTTP against a listener on loopback, with a door that answers every turn it is handed.
package splice.share

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import splice.core.testing.TestPorts
import splice.core.topology.ShareConfig
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

internal class ShareListenerTest {
    private val sharePort = TestPorts.reserve()
    private val http = HttpClient.newHttpClient()
    private lateinit var listener: ShareListener

    /** The turns the door was handed. */
    private val turns = AtomicInteger()

    private val door = object : ShareDoor {
        override suspend fun launch(): SharedDoorLaunch = SharedDoorLaunch(machine = "devbox", window = 200_000)

        override suspend fun answer(call: ApplicationCall, counting: Boolean) {
            turns.incrementAndGet()
            call.respondText("{}")
        }
    }

    @BeforeEach
    fun start() {
        listener = ShareListener(ShareConfig(enabled = true, port = sharePort), "devbox", door, Dispatchers.IO) {}
        listener.start()
    }

    @AfterEach
    fun stop() {
        listener.stop()
    }

    private fun send(
        path: String,
        body: String? = null,
        type: String = "application/json",
        extra: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$sharePort$path"))
            .header("Content-Type", type)
            .header("X-Claude-Code-Session-Id", "mac-session")
            .also { builder -> extra.forEach { (name, value) -> builder.header(name, value) } }
            .let { if (body == null) it.GET() else it.POST(HttpRequest.BodyPublishers.ofString(body)) }
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /** [path] asked with [host] as its Host header, as written: the JDK's client sets its own Host, so this goes over a
     *  socket of its own. The status line's code and the body. */
    private fun sendAs(host: String, path: String): Pair<Int, String> = Socket("127.0.0.1", sharePort).use { socket ->
        val request = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
        val answer = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        answer.substringAfter(' ').take(STATUS_DIGITS).toInt() to answer.substringAfter("\r\n\r\n")
    }

    /** The Anthropic error body Claude Code prints: its type and its message. */
    private fun error(body: String): Pair<String, String> {
        val error = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
        return error.getValue("type").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    private val turn = """{"model":"m","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}"""

    @Test
    fun `GET slash names the machine, with no command of its own`() {
        val (status, body) = send("/")
        assertEquals(200, status, body)
        val info = Json.parseToJsonElement(body).jsonObject
        assertEquals("devbox", info.getValue("machine").jsonPrimitive.content)
        assertEquals(0, info.getValue("commands").jsonArray.size, body)
    }

    @Test
    fun `every address that named one command is a 404 naming the door and the add, and reaches nothing`() {
        val asked = listOf(
            "/claudex/launch?auth=work" to null,
            "/claudex/launch" to null,
            "/claudex/v1/messages" to turn,
            "/claudex/v1/messages?beta=true" to turn,
            "/claudex/@work/v1/messages" to turn,
            "/claudex/statusline" to """{"session_id":"s"}""",
            "/astra/v1/messages/count_tokens" to turn,
        )
        for ((path, body) in asked) {
            val (status, answer) = send(path, body)
            assertEquals(404, status, "$path: $answer")
            val (type, message) = error(answer)
            assertEquals("not_found_error", type, "$path: $answer")
            val words = "devbox no longer shares one command at an address of its own: its door serves every model " +
                "it has. On the other machine, run: splice remote add http://127.0.0.1:$sharePort"
            assertTrue(message.endsWith(words), "$path: $message")
        }
        assertEquals(0, turns.get(), "a per-command address reached the door")
    }

    @Test
    fun `the add names the Host the request used, as it was sent`() {
        val (status, body) = sendAs("[::1]:$sharePort", "/claudex/launch")
        assertEquals(404, status, body)
        assertTrue(error(body).second.endsWith("splice remote add http://[::1]:$sharePort"), body)
    }

    @Test
    fun `a web page or a Host that names another machine is refused before any path is answered`() {
        val (page, pageBody) = send("/claudex/launch", extra = mapOf("Origin" to "http://page.example"))
        assertEquals(403, page, pageBody)
        assertFalse("splice remote add" in pageBody, pageBody)
        val (foreign, foreignBody) = sendAs("elsewhere.example:$sharePort", "/claudex/launch")
        assertEquals(403, foreign, foreignBody)
        assertFalse("splice remote add" in foreignBody, foreignBody)
    }

    @Test
    fun `the door still answers its launch, a turn and a count`() {
        assertEquals(200, send("/door/launch").first)
        assertEquals(200, send("/door/v1/messages", turn).first)
        assertEquals(200, send("/door/v1/messages/count_tokens", turn).first)
        assertEquals(2, turns.get())
    }

    // The listener faces the LAN, so a turn's Content-Type is whatever a client or a probe sends. Whatever it is, the
    // answer is an error Claude Code prints or the turn itself, never a 500 in Ktor's plain text (PR #247 review r2).
    @Test
    fun `a door turn of any content type is refused in an error Claude Code prints, or answered, never a 500`() {
        val random = Random(CONTENT_TYPE_SEED)
        val generated = List(GENERATED_TYPES) {
            List(random.nextInt(1, MAX_TYPE_CHARS)) { TYPE_CHARS[random.nextInt(TYPE_CHARS.length)] }.joinToString("")
        }.map(String::trim).filter(String::isNotEmpty)
        for (type in listOf("json", "text", "application", "/", "application/") + generated) {
            val (status, body) = send("/door/v1/messages", turn, type)
            assertTrue(status == 400 || status == 200, "Content-Type '$type' answered $status: $body")
            if (status == 400) assertTrue(error(body).second.contains("application/json"), "Content-Type '$type'")
            if ('/' !in type) assertEquals(400, status, "Content-Type '$type' names no media type: $body")
        }
    }

    @Test
    fun `a door turn sent as application json, in any case and with parameters, reaches the door`() {
        val random = Random(CONTENT_TYPE_SEED)
        repeat(JSON_VARIANTS) {
            val spelled = "application/json".map { if (random.nextBoolean()) it.uppercaseChar() else it }
            val type = spelled.joinToString("") + JSON_PARAMETERS[random.nextInt(JSON_PARAMETERS.size)]
            val (status, body) = send("/door/v1/messages", turn, type)
            assertEquals(200, status, "Content-Type '$type': $body")
        }
        assertEquals(JSON_VARIANTS, turns.get())
    }
}

// why: an HTTP status code is three digits.
private const val STATUS_DIGITS = 3

// why: a fixed seed, so a failing Content-Type comes back on every run and its value is in the message.
private const val CONTENT_TYPE_SEED = 20_261_026L

// why: generated values per run; each refusal is one loopback round trip.
private const val GENERATED_TYPES = 200

// why: long enough for a type, a subtype and a parameter or two.
private const val MAX_TYPE_CHARS = 24

// why: well-formed JSON types per run; each reaches the door.
private const val JSON_VARIANTS = 12

// why: the letters of application/json in both cases, digits, and every separator a media type or a parameter uses.
private const val TYPE_CHARS = "aApPlLiIcCtToOnNjJsS0123456789/;=,+-.*\" "

private val JSON_PARAMETERS = listOf("", "; charset=utf-8", ";charset=UTF-8", " ; x=1")
