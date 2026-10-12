// S8 #5, #16 (Oct 12, 2026): Settings' MCP rows. Per server the status names whether it is shared, why not, and which
// commands' sessions use it; Test starts a shared server and answers with the round trip and its tool count, or says
// why it could not. Driven over HTTP against a real host and a scripted child (FakeMcpServer).
package splice.app.control.api.diagnostics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ControlAuth
import splice.app.control.ControlRuntime
import splice.app.control.ControlServer
import splice.app.control.controlServerFor
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpAccessKey
import splice.client.mcp.McpSharing
import splice.control.mcp.FakeMcpServer
import splice.control.mcp.McpHost
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Path

class McpServerRowsRouteTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) { expectSuccess = false }
    private lateinit var control: ControlServer
    private lateinit var key: String
    private lateinit var sharing: McpSharing
    private lateinit var global: JsonObject
    private val port: Int get() = control.listeningPort

    @BeforeEach
    fun setUp(@TempDir tmp: Path) {
        val mgmt = MgmtKey(StatePaths(baseOverride = tmp.resolve("state")))
        key = mgmt.get()
        global = buildJsonObject {
            put("fake", FakeMcpServer.entry())
            put("remote", json.parseToJsonElement("""{"type":"http","url":"https://example.invalid/mcp"}"""))
            put("scoped", json.parseToJsonElement("""{"command":"npx","cwd":"/work/api"}"""))
            put("keyed", json.parseToJsonElement("""{"command":"npx","env":{"TOKEN":"${'$'}{GITHUB_TOKEN}"}}"""))
            put("streamy", json.parseToJsonElement("""{"type":"sse","url":"https://example.invalid/sse"}"""))
            put("socket", json.parseToJsonElement("""{"type":"ws","url":"wss://example.invalid/ws"}"""))
            put("broken", json.parseToJsonElement("""{"command":"/nonexistent/mcp-server"}"""))
            put("local", json.parseToJsonElement("""{"command":"npx","args":["--root","./repo"]}"""))
        }
        sharing = McpSharing(
            true,
            emptySet(),
            "http://127.0.0.1:0/mcp/",
            McpAccessKey(mgmt::get),
            DirectoryProbe { false },
        )
        control = controlServerFor(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(StatePaths(baseOverride = tmp.resolve("state"))),
            runtime = ControlRuntime(mcpHost = McpHost(sharing, { global }, log = { })),
            auth = ControlAuth(mgmtKey = mgmt, log = { }),
        )
        runBlocking { control.start() }
    }

    @AfterEach
    fun tearDown() {
        control.stop()
        client.close()
    }

    private suspend fun connect(head: String?): String {
        val reply = client.post("http://127.0.0.1:$port/mcp/fake") {
            header("Authorization", "Bearer $key")
            head?.let { header("X-Splice-Head", it) }
            contentType(ContentType.Application.Json)
            setBody(OPEN_FAKE_SESSION)
        }
        assertEquals(HttpStatusCode.OK, reply.status, reply.bodyAsText())
        return checkNotNull(reply.headers["Mcp-Session-Id"])
    }

    private suspend fun servers(): JsonObject = json.parseToJsonElement(
        client.get("http://127.0.0.1:$port/api/mcp") { header("Authorization", "Bearer $key") }.bodyAsText(),
    ).jsonObject.getValue("servers").jsonObject

    private suspend fun test(name: String, bearer: String = key) =
        client.post("http://127.0.0.1:$port/api/mcp/$name/test") { header("Authorization", "Bearer $bearer") }

    @Test
    fun `a shared server names the commands whose sessions use it, a server that is not shared says why`() =
        runBlocking {
            connect("alpha")
            connect("beta")
            connect("alpha")
            connect(null)
            val rows = servers()
            val fake = rows.getValue("fake").jsonObject
            assertEquals("true", fake.getValue("eligible").jsonPrimitive.content)
            assertEquals("4", fake.getValue("sessions").jsonPrimitive.content)
            assertEquals(listOf("alpha", "beta"), fake.getValue("commands").jsonArray.map { it.jsonPrimitive.content })
            val remote = rows.getValue("remote").jsonObject
            assertEquals("false", remote.getValue("eligible").jsonPrimitive.content)
            val reason = remote.getValue("reason").jsonPrimitive.content
            assertTrue(reason.contains("already serves many clients"), "$remote")
            // the page words the key, never the sentence: each refusal names its reason as a key and the value it names
            fun why(name: String) = rows.getValue(name).jsonObject.let {
                it.getValue("why").jsonPrimitive.content to it["value"]?.jsonPrimitive?.content
            }
            assertEquals("transport" to "https://example.invalid/mcp", why("remote"))
            assertEquals("cwd" to "/work/api", why("scoped"))
            assertEquals("variable" to "GITHUB_TOKEN", why("keyed"))
            assertEquals("relative_path" to "./repo", why("local"))
            assertFalse(fake.containsKey("why"), "a shared server has no reason")
        }

    @Test
    fun `the config written for a command tells the host which command it is`() {
        val written = sharing.rewrite()(global, "alpha").getValue("fake").jsonObject.getValue("headers").jsonObject
        assertEquals("alpha", written.getValue("X-Splice-Head").jsonPrimitive.content)
        assertEquals(
            "https://example.invalid/mcp",
            sharing.rewrite()(global, "alpha").getValue("remote").jsonObject.getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun `Test starts a server that is not running, lists its tools and leaves no session behind`() = runBlocking {
        val first = json.parseToJsonElement(test("fake").bodyAsText()).jsonObject
        assertEquals("true", first.getValue("ok").jsonPrimitive.content, "$first")
        assertEquals("true", first.getValue("started").jsonPrimitive.content, "it was not running")
        assertTrue(first.getValue("tools").jsonPrimitive.content.toInt() >= 1, "$first")
        assertTrue(first.containsKey("round_trip_ms"), "$first")
        assertEquals("0", servers().getValue("fake").jsonObject.getValue("sessions").jsonPrimitive.content)
        val second = json.parseToJsonElement(test("fake").bodyAsText()).jsonObject
        assertEquals("false", second.getValue("started").jsonPrimitive.content, "it was running by now: $second")
    }

    @Test
    fun `Test says why a server is not shared, and that an unknown one is not there`() = runBlocking {
        val remote = json.parseToJsonElement(test("remote").bodyAsText()).jsonObject
        assertEquals("false", remote.getValue("ok").jsonPrimitive.content)
        assertTrue(remote.getValue("error").jsonPrimitive.content.contains("already serves many clients"), "$remote")
        assertEquals("unshared", remote.getValue("kind").jsonPrimitive.content)
        val nobody = json.parseToJsonElement(test("nobody").bodyAsText()).jsonObject
        assertEquals("false", nobody.getValue("ok").jsonPrimitive.content)
        assertTrue(nobody.getValue("error").jsonPrimitive.content.contains("no such server"), "$nobody")
    }

    @Test
    fun `Test says a server that cannot be started was not started, by kind`() = runBlocking {
        val broken = json.parseToJsonElement(test("broken").bodyAsText()).jsonObject
        assertEquals("false", broken.getValue("ok").jsonPrimitive.content, "$broken")
        assertEquals("spawn", broken.getValue("kind").jsonPrimitive.content, "$broken")
        assertFalse(broken.containsKey("tools"), "$broken")
    }

    @Test
    fun `sse and websocket servers pass through untouched, each as one server for every agent`() = runBlocking {
        val rows = servers()
        for (name in listOf("streamy", "socket")) {
            val row = rows.getValue(name).jsonObject
            assertEquals("false", row.getValue("eligible").jsonPrimitive.content, "$name: $row")
            assertEquals("transport", row.getValue("why").jsonPrimitive.content, "$name: $row")
        }
        val written = sharing.rewrite()(global, "alpha")
        assertEquals(global.getValue("streamy"), written.getValue("streamy"), "an sse entry is written as declared")
        assertEquals(global.getValue("socket"), written.getValue("socket"), "a websocket entry is written as declared")
    }

    @Test
    fun `a hosted server is reachable on loopback and on no other address`() = runBlocking {
        val elsewhere = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
        org.junit.jupiter.api.Assumptions.assumeTrue(elsewhere != null, "this host has no non-loopback address")
        val refused = try {
            java.net.Socket(elsewhere, port).use { }
            false
        } catch (_: java.io.IOException) {
            true
        }
        assertTrue(refused, "the control listener, which serves /mcp, must not answer on $elsewhere")
        assertEquals(HttpStatusCode.OK, connectStatus())
    }

    private suspend fun connectStatus() = client.post("http://127.0.0.1:$port/mcp/fake") {
        header("Authorization", "Bearer $key")
        contentType(ContentType.Application.Json)
        setBody(OPEN_FAKE_SESSION)
    }.status

    @Test
    fun `Test is a management act, so the MCP key does not open it`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, test("fake", bearer = McpAccessKey { key }()).status)
        assertFalse(servers().getValue("fake").jsonObject.getValue("hosted").jsonPrimitive.content.toBoolean())
    }
}

private const val OPEN_FAKE_SESSION =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
        """"capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
