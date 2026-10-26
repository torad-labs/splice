// The share listener's answers when the head behind an address can't take a turn (docs/specs/lan-share.md, goal 5):
// the other machine's Claude Code is told which machine and which head, in an error it prints, and never that the
// server is "overloaded", which sent Claude Code into a silent retry on 2026-10-24. Driven over real HTTP against a
// listener on loopback, with heads whose ports nothing listens on.
package splice.share

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.testing.TestPorts
import splice.core.topology.ShareConfig
import splice.launch.LaunchSpec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.random.Random

internal class ShareListenerTest {
    private val sharePort = TestPorts.reserve()
    private val http = HttpClient.newHttpClient()
    private lateinit var listener: ShareListener

    private class Head(override val key: String, override val port: Int, private val up: Boolean) : SharedHead {
        override val command: String = key

        override fun running(): Boolean = up

        override fun launch(): LaunchSpec? = null

        override fun logins(): List<String> = listOf("primary")

        override fun pin(login: String, session: String): Boolean = login == "primary"

        override fun maxRequestBytes(): Int = 1_000_000

        override fun requestReadTimeoutMs(): Long = 5_000
    }

    @BeforeEach
    fun start(@TempDir state: Path) {
        listener = listening(StatePaths(baseOverride = state))
    }

    private fun listening(state: StatePaths) = ShareListener(
        ShareConfig(enabled = true, port = sharePort),
        object : SharedHeads {
            override val machine: String = "torad-server"

            override fun offered(): List<SharedHead> = listOf(
                Head("stopped", TestPorts.reserve(), up = false),
                Head("silent", TestPorts.reserve(), up = true),
            )
        },
        TurnKey(MgmtKey(state)),
        controlPort = TestPorts.reserve(),
        io = Dispatchers.IO,
        log = {},
    ).also { it.start() }

    @AfterEach
    fun stop() = listener.stop()

    private fun send(path: String, body: String? = null, type: String = "application/json"): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$sharePort$path"))
            .header("Content-Type", type)
            .header("X-Claude-Code-Session-Id", "mac-session")
            .let { if (body == null) it.GET() else it.POST(HttpRequest.BodyPublishers.ofString(body)) }
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /** The Anthropic error body Claude Code prints: its type and its message. */
    private fun error(body: String): Pair<String, String> {
        val error = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
        return error.getValue("type").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    private val turn = """{"model":"m","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}"""

    @Test
    fun `a turn for a head that is not running says so, in the server's name, and is never overloaded`() {
        val (status, body) = send("/stopped/v1/messages", turn)
        assertEquals(503, status, body)
        val (type, message) = error(body)
        assertNotEquals("overloaded_error", type)
        assertTrue(listOf("stopped", "torad-server", "not running").all(message::contains), message)
    }

    @Test
    fun `a turn for a head that does not answer says so, in the server's name, and is never overloaded`() {
        val (status, body) = send("/silent/@primary/v1/messages", turn)
        assertEquals(503, status, body)
        val (type, message) = error(body)
        assertNotEquals("overloaded_error", type)
        assertTrue(listOf("silent", "torad-server", "not answering").all(message::contains), message)
    }

    @Test
    fun `a launch for a head that is not running is refused before Claude Code starts`() {
        val (status, body) = send("/stopped/launch")
        assertEquals(503, status, body)
        assertTrue(error(body).second.contains("not running"), body)
    }

    @Test
    fun `an address naming a head this machine does not offer is told the ones it does`() {
        val (status, body) = send("/grok/v1/messages", turn)
        assertEquals(404, status, body)
        val message = error(body).second
        assertTrue(message.contains("grok") && message.contains("stopped") && message.contains("silent"), message)
    }

    // The listener faces the LAN, so a turn's Content-Type is whatever a client or a probe sends. Whatever it is, the
    // answer is an error Claude Code prints or the turn itself, never a 500 in Ktor's plain text (PR #247 review r2).
    @Test
    fun `a posted turn of any content type is refused in an error Claude Code prints, or relayed, never a 500`() {
        val random = Random(CONTENT_TYPE_SEED)
        val generated = List(GENERATED_TYPES) {
            List(random.nextInt(1, MAX_TYPE_CHARS)) { TYPE_CHARS[random.nextInt(TYPE_CHARS.length)] }.joinToString("")
        }.map(String::trim).filter(String::isNotEmpty)
        for (type in listOf("json", "text", "application", "/", "application/") + generated) {
            val (status, body) = send("/silent/v1/messages", turn, type)
            assertTrue(status == 400 || status == 503, "Content-Type '$type' answered $status: $body")
            val message = error(body).second
            if ('/' !in type) {
                assertEquals(400, status, "Content-Type '$type' names no media type: $body")
                assertTrue(message.contains("application/json"), "Content-Type '$type': $message")
            }
        }
    }

    @Test
    fun `a turn sent as application json, in any case and with parameters, reaches the head`() {
        val random = Random(CONTENT_TYPE_SEED)
        repeat(JSON_VARIANTS) {
            val spelled = "application/json".map { if (random.nextBoolean()) it.uppercaseChar() else it }
            val type = spelled.joinToString("") + JSON_PARAMETERS[random.nextInt(JSON_PARAMETERS.size)]
            val (status, body) = send("/silent/v1/messages", turn, type)
            assertEquals(503, status, "Content-Type '$type': $body")
            assertTrue(error(body).second.contains("not answering"), "Content-Type '$type': $body")
        }
    }
}

// why: a fixed seed, so a failing Content-Type comes back on every run and its value is in the message.
private const val CONTENT_TYPE_SEED = 20_261_026L

// why: generated values per run; each refusal is one loopback round trip.
private const val GENERATED_TYPES = 200

// why: long enough for a type, a subtype and a parameter or two.
private const val MAX_TYPE_CHARS = 24

// why: well-formed JSON types per run; each reaches the head's relay.
private const val JSON_VARIANTS = 12

// why: the letters of application/json in both cases, digits, and every separator a media type or a parameter uses.
private const val TYPE_CHARS = "aApPlLiIcCtToOnNjJsS0123456789/;=,+-.*\" "

private val JSON_PARAMETERS = listOf("", "; charset=utf-8", ";charset=UTF-8", " ; x=1")
