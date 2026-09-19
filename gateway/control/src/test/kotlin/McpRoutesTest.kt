import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.control.ControlServer
import splice.control.mcp.McpHost
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.launch.DirectoryProbe
import splice.core.launch.McpAccessKey
import splice.core.launch.McpSharing
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.io.path.writeText

/** The HTTP face of shared MCP hosting: bearer-guarded, session header on initialize, JSON answers,
 *  an SSE notification stream on GET, DELETE ends the session. The child is the same scripted
 *  python server McpHostTest uses. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpRoutesTest {

    private val port = freshMcpPort()
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var control: ControlServer
    private lateinit var key: String
    private lateinit var generatedConfig: String
    private lateinit var host: McpHost

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("mcp-routes")
        val script = tmp.resolve("fake_mcp.py")
        script.writeText(FAKE_MCP_SCRIPT)
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        val global = json.parseToJsonElement("""{"fake":{"command":"python3","args":["$script"]}}""").jsonObject
        val sharing = McpSharing(
            true,
            emptySet(),
            "http://127.0.0.1:$port/mcp/",
            McpAccessKey(mgmt::get),
            DirectoryProbe { false },
        )
        generatedConfig = sharing.plan(global).rewritten.toString()
        host = McpHost(sharing, { global }, log = { })
        control = ControlServer(
            port = port,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
            mcpHost = host,
        )
        control.start() // routed and bound before it returns: Ktor's default SEQUENTIAL startup (V4-139)
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    private suspend fun post(body: String, session: String? = null, bearer: String? = key) =
        client.post("http://127.0.0.1:$port/mcp/fake") {
            bearer?.let { header("Authorization", "Bearer $it") }
            session?.let { header("Mcp-Session-Id", it) }
            header("Accept", "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `initialize needs the bearer and mints a session, calls answer as json, delete ends it`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, post(INIT_MSG, bearer = null).status)
        val init = post(INIT_MSG)
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val session = checkNotNull(init.headers["Mcp-Session-Id"])
        assertEquals("1", json.parseToJsonElement(init.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Accepted, post(INITIALIZED_MSG, session).status)
        val list = post(LIST_MSG, session)
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("\"echo\""), list.bodyAsText())
        // V4-148: over HTTP too, an id the host does not know is adopted rather than refused; the
        // DELETEd session below is what still answers 404, because end means ended.
        assertEquals(HttpStatusCode.OK, post(LIST_MSG, "nope").status)
        val status = client.get("http://127.0.0.1:$port/api/mcp") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        assertTrue(status.contains("\"hosted\":true"), status)
        val wrongVersion = client.delete("http://127.0.0.1:$port/mcp/fake") {
            header("Authorization", "Bearer $key")
            header("Mcp-Session-Id", session)
            header("MCP-Protocol-Version", "2024-11-05")
        }
        assertEquals(HttpStatusCode.BadRequest, wrongVersion.status)
        assertEquals(HttpStatusCode.OK, post(LIST_MSG, session).status, "a refused DELETE ends nothing")
        val del = client.delete("http://127.0.0.1:$port/mcp/fake") {
            header("Authorization", "Bearer $key")
            header("Mcp-Session-Id", session)
            header("MCP-Protocol-Version", "2025-11-25")
        }
        assertEquals(HttpStatusCode.OK, del.status)
        assertEquals(HttpStatusCode.NotFound, post(LIST_MSG, session).status)
        // Ended session + the old negotiated version on GET and DELETE: 404, never 400.
        val gone = client.get("http://127.0.0.1:$port/mcp/fake") {
            header("Authorization", "Bearer $key")
            header("Mcp-Session-Id", session)
            header("MCP-Protocol-Version", "2025-11-25")
        }
        assertEquals(HttpStatusCode.NotFound, gone.status)
        val again = client.delete("http://127.0.0.1:$port/mcp/fake") {
            header("Authorization", "Bearer $key")
            header("Mcp-Session-Id", session)
            header("MCP-Protocol-Version", "2025-11-25")
        }
        assertEquals(HttpStatusCode.NotFound, again.status)
    }

    @Test
    fun `MCP scoped bearer connects without granting management access`() = runBlocking {
        val scoped = McpAccessKey { key }()
        val headers = json.parseToJsonElement(generatedConfig).jsonObject["fake"]!!.jsonObject["headers"]!!.jsonObject
        assertEquals("Bearer $scoped", headers["Authorization"]!!.jsonPrimitive.content)
        assertFalse(generatedConfig.contains(key), "generated MCP config must not contain the management secret")
        assertEquals(HttpStatusCode.OK, post(INIT_MSG, bearer = scoped).status)
        val management = client.get("http://127.0.0.1:$port/api/mcp") {
            header("Authorization", "Bearer $scoped")
        }
        assertEquals(HttpStatusCode.Unauthorized, management.status)
        assertEquals(HttpStatusCode.OK, post(INIT_MSG).status, "existing management clients keep access")
    }

    @Test
    fun `get streams the child's notifications as sse events`() = runBlocking {
        val session = checkNotNull(post(INIT_MSG).headers["Mcp-Session-Id"])
        client.prepareGet("http://127.0.0.1:$port/mcp/fake") {
            header("Authorization", "Bearer $key")
            header("Mcp-Session-Id", session)
            header("Accept", "text/event-stream")
        }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Content-Type"].orEmpty().startsWith("text/event-stream"))
            val body = response.bodyAsChannel()
            assertEquals(": open", withTimeout(STREAM_WAIT_MS) { body.readUTF8Line() })
            post(NOTIFY_MSG, session)
            val lines = mutableListOf<String>()
            while (lines.none { it.startsWith("data: ") } && lines.size < MAX_SSE_LINES) {
                lines += withTimeout(STREAM_WAIT_MS) { body.readUTF8Line() } ?: break
            }
            assertTrue(lines.any { it.startsWith("data: ") && it.contains("list_changed") }, lines.toString())
        }
        val unknown = client.get("http://127.0.0.1:$port/mcp/fake") {
            header("Authorization", "Bearer $key")
            header("Mcp-Session-Id", "nope")
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
    }
}

private const val STREAM_WAIT_MS = 5_000L
private const val MAX_SSE_LINES = 6
private const val INITIALIZED_MSG = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
private const val LIST_MSG = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
private const val NOTIFY_MSG =
    """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"echo","arguments":{"op":"notify"}}}"""
private const val INIT_MSG =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""

private const val FAKE_MCP_SCRIPT = """
import json, os, sys
def send(o):
    sys.stdout.write(json.dumps(o) + "\n"); sys.stdout.flush()
for line in sys.stdin:
    line = line.strip()
    if not line: continue
    m = json.loads(line)
    method = m.get("method"); rid = m.get("id")
    if method == "initialize":
        send({"jsonrpc":"2.0","id":rid,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}})
    elif method == "tools/list":
        send({"jsonrpc":"2.0","id":rid,"result":{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}})
    elif method == "tools/call":
        args = m["params"].get("arguments", {})
        if args.get("op") == "notify":
            send({"jsonrpc":"2.0","method":"notifications/tools/list_changed"})
        send({"jsonrpc":"2.0","id":rid,"result":{"content":[{"type":"text","text":"pid=%d" % os.getpid()}]}})
"""

private fun freshMcpPort(): Int = ServerSocket(0).use { it.localPort }
