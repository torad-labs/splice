// NEW: V4-175 — the shape of a refused wrap, pinned on the DAEMON side because two callers now
// read it and neither can see this code.
//
// The wizard (`splice setup`, :app) posts to this route and prints the reason it gets back; the
// console (webui) does the same through `request()`. Both lift the sentence out of
// `{"error": "<reason>"}`, which is the control plane's envelope at every refusal site — and the
// console was lifting `error.message` instead, so every daemon sentence reached the screen as
// `HTTP 409` until this row. That was a bug in the READER, and the only reason it survived is that
// nothing pinned the WRITER: a reader can be fixed to match whatever it finds, and the next one
// will guess again. This arm is the writer's side of that contract.
package splice.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.net.ServerSocket
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaudeHeadRefusalTest {

    private val client = HttpClient(CIO)
    private val port = ServerSocket(0).use { it.localPort }
    private lateinit var key: String
    private lateinit var control: ControlServer

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("v4175")
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        // NO heads: this is the state a wizard reaches when `splice add claude` refused and the
        // lane answer was still `wrap` — the case the wizard must report rather than retry.
        control = ControlServer(
            port = port,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = {},
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() = runBlocking {
        control.stop()
        client.close()
    }

    @Test
    fun `a wrap with no claude head refuses in one sentence under a flat error key`() = runBlocking {
        val response = client.post("http://127.0.0.1:$port/api/claude-head/wrap") {
            header("Authorization", "Bearer $key")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        // A STRING, not an object. `error.message` is what the console read for a year of routes
        // and it is not what any of them write; a nested envelope here would silently un-fix it.
        val error = body["error"]!!.jsonPrimitive
        assertTrue(error.isString, "error must be a string sentence, got ${body["error"]}")
        assertTrue(
            "claude-splice" in error.content && "not configured" in error.content,
            "the refusal must name the head and say what is wrong: ${error.content}",
        )
        // The wizard prints this verbatim, so it has to read as a sentence to a person, not as a
        // code plus a hint that the reason is elsewhere.
        assertTrue(error.content.length > "unconfigured".length, error.content)
    }
}
