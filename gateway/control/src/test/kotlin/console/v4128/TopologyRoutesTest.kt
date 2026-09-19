// NEW: V4-128 — GET and PUT /api/topology THROUGH A REAL ControlServer over HTTP with the mgmt key, so a
// lost routing line or an unassigned `topology` property fails here by name. The parser is a lookup
// table from exact text to topology (control has no TOML parser); the writer's bytes against the real
// loader are app/console/v4128's.
//
// THE SECRETS CONTRACT, all three spellings: a masked header keeps the stored value (and the stored
// value reaches the file without ever crossing the wire), an absent header is removed, and an empty
// string is written as an empty value. A masked header the file does not store is a finding.
package console.v4128

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.ControlServer
import splice.control.TopologyStale
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L
private const val WINDOW = 128_000L
private const val SECRET = "sk-secret"
private const val ROUTE = "/api/topology"
private const val HEADERS = "extra_headers = { x-api-key = \"sk-secret\" }\n"
private const val FILE = "[providers.ex]\n$HEADERS\n[heads.ex]\nport = 8801\n"

/** One request: method, body (null = none), and whether to send the mgmt key. */
private fun interface Call {
    suspend operator fun invoke(method: HttpMethod, body: String?, authorized: Boolean): HttpResponse
}

class TopologyRoutesTest {

    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, FILE) } }

    private fun topology(port: Int = 8801, headers: Map<String, String> = mapOf("x-api-key" to SECRET)) = Topology(
        providers = mapOf(
            "ex" to ProviderConfig(
                Dialect.OPENAI_CHAT,
                "https://api.example.com/v1",
                AuthConfig("api-key", env = "EX_KEY"),
                extraHeaders = headers,
                models = listOf(ModelEntry("m1", contextWindow = WINDOW)),
            ),
        ),
        heads = mapOf("ex" to HeadConfig("ex", port, "ex/", "m1")),
    )

    /** Every file text this test expects the writer to compose, and what it declares. */
    private val texts by lazy {
        mapOf(
            FILE to topology(),
            FILE.replace("8801", "8802") to topology(port = 8802),
            FILE.replace(HEADERS, "") to topology(headers = emptyMap()),
            FILE.replace(SECRET, "") to topology(headers = mapOf("x-api-key" to "")),
        )
    }

    private fun serve(wired: Boolean, test: suspend (Call) -> Unit) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val port = ServerSocket(0).use { it.localPort }
        val control = ControlServer(
            port = port,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
            topologyStale = TopologyStale { true },
        )
        if (wired) {
            control.ports.topology = TopologyWriter(
                file,
                TopologyParse { text -> texts[text] ?: throw IllegalArgumentException("not a text this test predicted") },
            )
        }
        control.start()
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(POLL_MS)
                    test { method, body, authorized ->
                        client.request("http://127.0.0.1:$port$ROUTE") {
                            this.method = method
                            if (authorized) header("Authorization", "Bearer ${mgmt.get()}")
                            body?.let { setBody(it) }
                        }
                    }
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** The GET payload's topology, edited as text, wrapped as a PUT body. */
    private suspend fun edited(call: Call, edit: (String) -> String): String {
        val read = json(call(HttpMethod.Get, null, true).bodyAsText())
        return "{\"topology\":${edit(read.getValue("topology").toString())}}"
    }

    private suspend fun put(call: Call, body: String): JsonObject {
        val reply = call(HttpMethod.Put, body, true)
        assertEquals(200, reply.status.value, reply.bodyAsText())
        return json(reply.bodyAsText())
    }

    private fun findings(reply: JsonObject): List<String> =
        reply.getValue("findings").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }

    @Test
    fun `the read serves the file's path, its topology with every header masked, and the stale flag`() {
        serve(wired = true) { call ->
            val reply = call(HttpMethod.Get, null, true)
            assertEquals(200, reply.status.value)
            val body = json(reply.bodyAsText())
            assertEquals(file.toString(), body.getValue("path").jsonPrimitive.content)
            assertTrue(body.getValue("stale").jsonPrimitive.boolean)
            val headers = body.getValue("topology").jsonObject.getValue("providers").jsonObject.getValue("ex")
                .jsonObject.getValue("extra_headers").jsonObject
            assertEquals("********", headers.getValue("x-api-key").jsonPrimitive.content)
            assertFalse(reply.bodyAsText().contains(SECRET), "the secret never crosses the wire")
            assertEquals(401, call(HttpMethod.Get, null, false).status.value, "the read is guarded")
        }
    }

    @Test
    fun `a write with the mask kept lands the stored secret and answers with its backup`() {
        serve(wired = true) { call ->
            val reply = put(call, edited(call) { it.replace("\"port\":8801", "\"port\":8802") })
            assertTrue(reply.getValue("ok").jsonPrimitive.boolean, reply.toString())
            assertTrue(reply.getValue("restart_required").jsonPrimitive.boolean)
            assertEquals(FILE, Files.readString(Path.of(reply.getValue("backup_path").jsonPrimitive.content)))
            assertEquals(FILE.replace("8801", "8802"), Files.readString(file))
            assertEquals(401, call(HttpMethod.Put, "{}", false).status.value, "the write is guarded")
        }
    }

    @Test
    fun `an absent header is removed and an empty one is written empty`() {
        serve(wired = true) { call ->
            val empty = put(call, edited(call) { it.replace("\"x-api-key\":\"********\"", "\"x-api-key\":\"\"") })
            assertTrue(empty.getValue("ok").jsonPrimitive.boolean, empty.toString())
            assertEquals(FILE.replace(SECRET, ""), Files.readString(file))
        }
        Files.writeString(file, FILE)
        serve(wired = true) { call ->
            val absent = put(call, edited(call) { it.replace("\"x-api-key\":\"********\"", "") })
            assertTrue(absent.getValue("ok").jsonPrimitive.boolean, absent.toString())
            assertEquals(FILE.replace(HEADERS, ""), Files.readString(file))
        }
    }

    @Test
    fun `refusals answer findings and leave the file byte-identical`() {
        serve(wired = true) { call ->
            val stranger = put(call, edited(call) { it.replace("\"x-api-key\":", "\"x-other\":") })
            assertEquals(listOf("providers.ex.extra_headers.x-other"), findings(stranger))
            val unknown = put(call, edited(call) { it.replace("\"port\":8801", "\"port\":8801,\"wibble\":1") })
            assertFalse(unknown.getValue("ok").jsonPrimitive.boolean)
            assertEquals(listOf("topology"), findings(unknown))
            assertTrue(unknown.toString().contains("wibble"), "the finding names the unknown key: $unknown")
            val port = put(call, edited(call) { it.replace("\"port\":8801", "\"port\":0") })
            assertEquals(listOf("heads.ex.port"), findings(port))
            assertTrue(port.getValue("restart_required").jsonPrimitive.boolean)
            assertEquals(400, call(HttpMethod.Put, "{\"not\":1}", true).status.value)
            assertEquals(FILE, Files.readString(file))
        }
    }

    @Test
    fun `an unwired writer and an unparseable file are named errors`() {
        serve(wired = false) { call ->
            val reply = call(HttpMethod.Get, null, true)
            assertEquals(503, reply.status.value)
            assertEquals("""{"error":"the topology writer is not wired into this control plane"}""", reply.bodyAsText())
        }
        Files.writeString(file, "not a text the parser knows\n")
        serve(wired = true) { call ->
            val reply = call(HttpMethod.Get, null, true)
            assertEquals(500, reply.status.value)
            assertTrue(reply.bodyAsText().contains("splice.toml does not parse"), reply.bodyAsText())
        }
    }
}
