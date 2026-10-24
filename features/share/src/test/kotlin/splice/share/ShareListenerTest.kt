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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    private fun send(path: String, body: String? = null): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$sharePort$path"))
            .header("Content-Type", "application/json")
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
}
