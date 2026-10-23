// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). The Sharing page's rows for this machine THROUGH A
// REAL ControlServer over HTTP with the mgmt key: GET /api/share as the file holds it, PUT /api/share turning the real
// share listener on and off with no restart, the offered commands stored as the page chose them, and the refusals in
// words. Milestone 5: a machine that turns sharing on is found by one looking on the network, over real multicast DNS
// and the real TLS probe, and is not found once it turns it off. splice.toml, the state folder and the certificate live in a temp folder the routes reach through the daemon's
// environment (SharingParts.env), so nothing here reads or writes the machine's own.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.share.ShareHostName
import splice.share.ShareListener
import splice.share.ShareStart
import splice.share.SharedCatalogs
import splice.share.SharedQuotas
import splice.share.remote.RemoteDiscovery
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteTrust
import splice.topology.TopologyLoader
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

private const val SHARE_TIMEOUT_MS = 20_000L
private const val SHARE_POLL_MS = 20L

// Two heads on their own key, which this machine can offer, and one that forwards the caller's own Claude login,
// which it cannot.
private val SHARE_FILE = """
    [providers.mock]
    dialect = "anthropic-passthrough"
    base_url = "http://127.0.0.1:1"
    auth = { kind = "api-key", env = "MOCK_API_KEY" }
    [[providers.mock.models]]
    id = "mock-1"
    context_window = 200000

    [providers.anthropic]
    dialect = "anthropic-passthrough"
    base_url = "https://api.anthropic.com"
    auth = { kind = "client" }
    [[providers.anthropic.models]]
    id = "claude-x"
    context_window = 200000

    [heads.one]
    provider = "mock"
    port = 3901
    discovery_prefix = "claude-one--"
    pinned_model = "mock-1"
    [heads.one.claude]
    command = "claude-one"

    [heads.two]
    provider = "mock"
    port = 3902
    discovery_prefix = "claude-two--"
    pinned_model = "mock-1"
    [heads.two.claude]
    command = "claude-two"

    [heads.mine]
    provider = "anthropic"
    port = 3903
    discovery_prefix = "claude-mine--"
    pinned_model = "claude-x"
    [heads.mine.claude]
    command = "claude-mine"
""".trimIndent() + "\n"

/** One request: method, path, body (null = none), and whether to send the mgmt key. */
private fun interface ShareCall {
    suspend operator fun invoke(method: HttpMethod, path: String, body: String?, authorized: Boolean): HttpResponse
}

class SharingRoutesTest {

    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, SHARE_FILE) } }

    private val env by lazy {
        val vars = mapOf(
            "SPLICE_CONFIG" to file.toString(),
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
            "HOME" to tmp.toString(),
        )
        EnvReader { vars[it] }
    }

    private fun serve(wired: Boolean = true, test: suspend (ShareCall) -> Unit) {
        val paths = StatePaths(envReader = env)
        val mgmt = MgmtKey(paths)
        val config = ConfigService(paths)
        val control = controlServerWith(
            heads = emptyMap(),
            config = config,
            health = topologyHealthFor(emptyMap()),
            auth = ControlAuth(mgmtKey = mgmt, log = { }),
        )
        val listener = ShareListener(
            ShareStart(TopologyLoader.parse(SHARE_FILE), paths, mgmt, config) { },
            SharedCatalogs { null },
            SharedQuotas { null },
            paths,
        ) { }
        if (wired) control.ports.sharing = SharingParts(listener, remotes = null, env = env)
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(SHARE_TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(SHARE_POLL_MS)
                    test { method, path, body, authorized ->
                        client.request("http://127.0.0.1:$port$path") {
                            this.method = method
                            if (authorized) header("Authorization", "Bearer ${mgmt.get()}")
                            body?.let { setBody(it) }
                        }
                    }
                }
            }
        } finally {
            client.close()
            runBlocking { listener.stop() }
            control.stop()
        }
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun read(call: ShareCall): JsonObject {
        val reply = call(HttpMethod.Get, "/api/share", null, true)
        assertEquals(200, reply.status.value, reply.bodyAsText())
        return json(reply.bodyAsText())
    }

    private suspend fun put(call: ShareCall, body: String): JsonObject {
        val reply = call(HttpMethod.Put, "/api/share", body, true)
        assertEquals(200, reply.status.value, reply.bodyAsText())
        return json(reply.bodyAsText())
    }

    private fun commands(view: JsonObject): Map<String, JsonObject> =
        view.getValue("sharing").jsonObject.getValue("commands").jsonArray
            .associate { it.jsonObject.getValue("key").jsonPrimitive.content to it.jsonObject }

    private fun listening(reply: JsonObject): Boolean =
        reply.getValue("share").jsonObject.getValue("sharing").jsonObject.getValue("listening").jsonPrimitive.boolean

    private fun answers(port: Int): Boolean = runCatching { Socket("127.0.0.1", port).close() }.isSuccess

    /** What a machine looking for sharing machines finds at [port], scanning as `splice remote` does until [wanted]
     *  says the answer is the one awaited. Of its sources only multicast DNS can name this machine: the subnet and the
     *  tailnet leave out the machine looking. */
    private suspend fun looked(port: Int, wanted: (RemoteFound?) -> Boolean): RemoteFound? {
        val looking = RemoteDiscovery(RemoteHttp(RemoteTrust(null, "a sharing machine")))
        while (true) {
            val found = looking.scan(port).firstOrNull { it.port == port }
            if (wanted(found)) return found
            delay(SHARE_POLL_MS)
        }
    }

    @Test
    fun `the read is guarded and says the file's sharing, each command, and why one cannot be offered`() {
        serve { call ->
            assertEquals(401, call(HttpMethod.Get, "/api/share", null, false).status.value, "the read is guarded")
            val view = read(call)
            val sharing = view.getValue("sharing").jsonObject
            assertFalse(sharing.getValue("enabled").jsonPrimitive.boolean)
            assertFalse(sharing.getValue("listening").jsonPrimitive.boolean)
            val commands = commands(view)
            assertEquals(setOf("one", "two", "mine"), commands.keys)
            assertEquals("claude-one", commands.getValue("one").getValue("command").jsonPrimitive.content)
            assertTrue(commands.getValue("one").getValue("offered").jsonPrimitive.boolean, "no list offers them all")
            assertFalse(commands.getValue("mine").getValue("offered").jsonPrimitive.boolean)
            assertEquals(
                "it forwards the caller's own Claude login, so there is nothing to share",
                commands.getValue("mine").getValue("why").jsonPrimitive.content,
            )
            assertEquals(0, view.getValue("joined").jsonArray.size)
            assertEquals(0, view.getValue("remotes").jsonArray.size)
        }
    }

    @Test
    fun `turning sharing on opens the listener at once, and off closes it, with no restart`() {
        val port = TestPorts.reserve()
        serve { call ->
            assertEquals(401, call(HttpMethod.Put, "/api/share", "{}", false).status.value, "the write is guarded")
            val on = put(call, "{\"enabled\":true,\"port\":$port}")
            assertTrue(on.getValue("ok").jsonPrimitive.boolean, on.toString())
            assertTrue(listening(on), on.toString())
            val pin = on.getValue("share").jsonObject.getValue("sharing").jsonObject.getValue("fingerprint")
            assertTrue(pin.jsonPrimitive.content.isNotBlank(), "the pin a joining machine compares")
            assertTrue(answers(port), "the listener is open on the port the page asked for")
            val stored = TopologyLoader.parse(Files.readString(file)).share
            assertTrue(stored.enabled)
            assertEquals(port, stored.port)

            val off = put(call, "{\"enabled\":false}")
            assertFalse(listening(off), off.toString())
            assertFalse(answers(port), "the listener closed")
            assertFalse(TopologyLoader.parse(Files.readString(file)).share.enabled)
        }
    }

    @Test
    fun `a machine sharing is found on its network at its port and certificate, and not once it stops`() {
        val port = TestPorts.reserve()
        serve { call ->
            val on = put(call, "{\"enabled\":true,\"port\":$port}")
            val sharing = on.getValue("share").jsonObject.getValue("sharing").jsonObject
            val pin = sharing.getValue("fingerprint").jsonPrimitive
            val found = looked(port) { it != null }
            assertEquals(ShareHostName().short(), found?.info?.server)
            assertEquals(pin.content, found?.info?.fingerprint, "the certificate met is the one the page shows")

            put(call, "{\"enabled\":false}")
            assertNull(looked(port) { it == null }, "a machine that stopped sharing is no longer advertised")
        }
    }

    @Test
    fun `one command offered is stored by name, and every one offered is stored as none named`() {
        serve { call ->
            val one = put(call, "{\"heads\":[\"one\"]}")
            val commands = commands(one.getValue("share").jsonObject)
            assertTrue(commands.getValue("one").getValue("offered").jsonPrimitive.boolean)
            assertFalse(commands.getValue("two").getValue("offered").jsonPrimitive.boolean)
            assertEquals(listOf("one"), TopologyLoader.parse(Files.readString(file)).share.heads)

            put(call, "{\"heads\":[\"one\",\"two\"]}")
            assertNull(TopologyLoader.parse(Files.readString(file)).share.heads, "a command added later is offered too")
        }
    }

    @Test
    fun `a body the route cannot read is a 400 and leaves the file byte-identical`() {
        serve { call ->
            val reply = call(HttpMethod.Put, "/api/share", "{nope", true)
            assertEquals(400, reply.status.value)
            assertTrue(json(reply.bodyAsText()).getValue("error").jsonPrimitive.content.startsWith("the body must be"))
            assertEquals(SHARE_FILE, Files.readString(file))
        }
    }

    @Test
    fun `revoking a machine that never joined says so in words`() {
        serve { call ->
            val reply = call(HttpMethod.Delete, "/api/share/machines/nobody", null, true)
            assertEquals(200, reply.status.value)
            val body = json(reply.bodyAsText())
            assertFalse(body.getValue("ok").jsonPrimitive.boolean)
            val said = body.getValue("said").jsonArray.single().jsonObject
            assertEquals("no machine named 'nobody' has joined", said.getValue("text").jsonPrimitive.content)
            assertTrue(said.getValue("bad").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `unwired, the read answers 503 by name`() {
        serve(wired = false) { call ->
            val reply = call(HttpMethod.Get, "/api/share", null, true)
            assertEquals(503, reply.status.value)
            assertEquals(
                "sharing is not wired in this daemon",
                json(reply.bodyAsText()).getValue("error").jsonPrimitive.content,
            )
        }
    }
}
