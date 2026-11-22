// The share listener offers this machine's door and nothing else (docs/specs/door-network.md): `GET /` names the
// machine with no command of its own, and any command's address is a 404 in the Anthropic shape, in words a person can
// act on and never "overloaded". Driven over real HTTP against a listener on loopback.
package splice.share

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.testing.TestPorts
import splice.core.topology.ShareConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal class ShareListenerTest {
    private val sharePort = TestPorts.reserve()
    private val http = HttpClient.newHttpClient()
    private var listener: ShareListener? = null

    private val door = object : ShareDoor {
        override suspend fun launch(): SharedDoorLaunch? =
            SharedDoorLaunch("devbox", 500_000, "gpt-6-astra", emptyList())

        override suspend fun answer(call: ApplicationCall, counting: Boolean) {
            call.respondText(if (counting) "counted" else "answered")
        }
    }

    private fun listening(withDoor: Boolean = true) {
        listener = ShareListener(
            ShareConfig(enabled = true, port = sharePort),
            machine = "devbox",
            io = Dispatchers.IO,
            log = {},
            door = door.takeIf { withDoor },
        ).also { it.start() }
    }

    @AfterEach
    fun stop() {
        listener?.stop()
        listener = null
    }

    private fun send(path: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$sharePort$path"))
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /** The Anthropic error body Claude Code prints: its type and its message. */
    private fun error(body: String): Pair<String, String> {
        val error = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
        return error.getValue("type").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    @Test
    fun `GET names this machine and offers no command of its own`() {
        listening()
        val (status, body) = send("/")
        assertEquals(200, status, body)
        assertTrue(body.contains("\"machine\":\"devbox\""), body)
        assertTrue(body.contains("\"commands\":[]"), body)
    }

    @Test
    fun `a command's own address is a 404 naming the door and the add to run, never overloaded`() {
        listening()
        for (path in listOf(
            "/claudex/launch",
            "/claudex/v1/messages",
            "/claudex/@work/v1/messages",
            "/claudex/statusline",
        )) {
            val (status, body) = send(path)
            assertEquals(404, status, "$path: $body")
            val (type, message) = error(body)
            assertEquals("not_found_error", type, "$path: $body")
            assertNotEquals("overloaded_error", type)
            assertTrue(listOf("door", "splice remote add http://127.0.0.1:$sharePort").all(message::contains), message)
        }
    }

    @Test
    fun `a web page is refused before any route answers`() {
        listening()
        val (status, body) = send("/claudex/launch", headers = mapOf("Origin" to "http://page.example"))
        assertEquals(403, status, body)
    }

    @Test
    fun `a daemon with no door says this machine runs none`() {
        listening(withDoor = false)
        val (status, body) = send("/door/launch")
        assertEquals(404, status, body)
        assertTrue(body.contains("devbox runs no door"), body)
    }
}
