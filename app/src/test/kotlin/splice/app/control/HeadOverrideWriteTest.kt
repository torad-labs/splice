// PATCH /api/config?head=<key> THROUGH A REAL ControlServer, on a real splice.toml read by the real topology
// loader: the value a command runs with is set, changed and cleared where splice reads it (its provider's table for a
// knob a turn is served by, so every command on that provider runs with it; the command's own table for a display or
// trace knob), the operator's comments survive each write, a value the daemon would ignore refuses the whole write, and
// the response says which knobs act now. Until Nov 21, 2026 this pinned every value to the command's own table, which
// gateway-no-heads made a boot finding, so the write it pinned was refused.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.topology.TopologyStale
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.topology.TopologyLoader
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

private const val TIMEOUT_MS = 20_000L
private const val POLL_MS = 20L
private const val ROUTE = "/api/config?head=local"
private val TOML = """
    # my own note, above the provider
    [providers.local]
    dialect = "openai-chat"
    base_url = "http://127.0.0.1:3105/v1"
    auth = { kind = "api-key", env = "LOCAL_API_KEY" }
    [[providers.local.models]]
    id = "m"
    label = "M"
    context_window = 200000

    [heads.local]
    provider = "local"
    port = 3102
    discovery_prefix = "claude-local--"
    pinned_model = "m"  # the model I chose

    [heads.second]
    provider = "local"
    port = 3103
    discovery_prefix = "claude-local--"
    pinned_model = "m"
    [heads.second.claude]
    command = "claude-second"
""".trimIndent() + "\n"

class HeadOverrideWriteTest {
    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, TOML) } }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun serve(test: suspend (suspend (String, String) -> HttpResponse, ConfigService) -> Unit) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val config = ConfigService(paths)
        val control = controlServerWith(
            heads = emptyMap(),
            config = config,
            health = topologyHealthFor(emptyMap(), topologyStale = TopologyStale { true }),
            auth = ControlAuth(mgmtKey = mgmt, log = { }),
        )
        control.ports.topology = TopologyWriter(file, tmp.resolve("backups"), TopologyParse(TopologyLoader::parse))
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(POLL_MS)
                    test({ route, body ->
                        client.request("http://127.0.0.1:$port$route") {
                            method = HttpMethod.Patch
                            header("Authorization", "Bearer ${mgmt.get()}")
                            setBody(body)
                        }
                    }, config)
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    /** The command's own table, and its provider's, as splice.toml holds them now. */
    private fun own(): Map<String, String> =
        TopologyLoader.parse(Files.readString(file)).heads.getValue("local").overrides

    private fun provider(): Map<String, String> =
        TopologyLoader.parse(Files.readString(file)).providers.getValue("local").overrides

    @Test
    fun `a value a turn is served by is set, changed and cleared on the provider, for every command on it`() {
        serve { patch, config ->
            val set = patch(ROUTE, """{"maxInflight":"3"}""")
            assertEquals(200, set.status.value, set.bodyAsText())
            assertEquals("3", provider()["maxInflight"], "the provider holds it, where the gateway reads it")
            assertEquals(null, own()["maxInflight"], "the command's own table holds no second copy")
            assertEquals(3, config.getConfig("local").maxInflight, "the command follows the write")
            assertEquals(3, config.getConfig("second").maxInflight, "and so does the other command on that provider")
            assertFalse(config.getConfig().maxInflight == 3, "the global value is untouched")
            val told = json(set.bodyAsText()).getValue("provider").jsonObject
            assertEquals("local", told.getValue("key").jsonPrimitive.content)
            assertEquals(listOf("maxInflight"), told.getValue("knobs").jsonArray.map { it.jsonPrimitive.content })
            val commands = told.getValue("commands").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("local", "second"), commands, "every command that reads it from there")
            val text = Files.readString(file)
            assertTrue(text.contains("# my own note, above the provider") && text.contains("# the model I chose"))

            assertEquals(200, patch(ROUTE, """{"maxInflight":"5"}""").status.value)
            assertEquals("5", provider()["maxInflight"])
            assertEquals(5, config.getConfig("local").maxInflight)

            assertEquals(200, patch(ROUTE, """{"maxInflight":null}""").status.value)
            assertEquals(null, provider()["maxInflight"])
            assertEquals(config.getConfig().maxInflight, config.getConfig("local").maxInflight, "falls back to global")
            assertEquals(config.getConfig().maxInflight, config.getConfig("second").maxInflight)
        }
    }

    @Test
    fun `a trace setting stays the command's own, and the answer says it waits for a restart`() {
        serve { patch, _ ->
            val set = patch(ROUTE, """{"traceRetentionDays":"3"}""")
            assertEquals(200, set.status.value, set.bodyAsText())
            assertEquals("3", own()["traceRetentionDays"])
            assertEquals(null, provider()["traceRetentionDays"], "the other command on the provider keeps its own")
            val reply = json(set.bodyAsText())
            assertTrue(reply.getValue("provider").jsonObject.getValue("knobs").jsonArray.isEmpty())
            val restart = reply.getValue("restart_required").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("traceRetentionDays"), restart)
        }
    }

    @Test
    fun `daemon timing refuses a head override and the global write names its required restart`() {
        serve { patch, config ->
            val refused = patch(ROUTE, """{"upstreamTimeoutMs":"120000"}""")
            assertEquals(400, refused.status.value)
            assertTrue(json(refused.bodyAsText()).getValue("rejected").jsonObject.containsKey("upstreamTimeoutMs"))
            assertEquals(TOML, Files.readString(file))

            val mixed = patch(ROUTE, """{"maxInflight":"3","upstreamTimeoutMs":"120000"}""")
            assertEquals(400, mixed.status.value)
            val mixedReply = json(mixed.bodyAsText())
            assertTrue(mixedReply.getValue("applied").jsonObject.isEmpty())
            assertTrue(mixedReply.getValue("rejected").jsonObject.containsKey("upstreamTimeoutMs"))
            assertEquals(TOML, Files.readString(file))
            assertEquals(config.getConfig().maxInflight, config.getConfig("local").maxInflight)

            val accepted = patch("/api/config", """{"upstreamTimeoutMs":"120000"}""")
            assertEquals(200, accepted.status.value)
            val reply = json(accepted.bodyAsText())
            val restart = reply.getValue("restart_required").jsonArray.map { it.jsonPrimitive.content }
            assertTrue("upstreamTimeoutMs" in restart, restart.toString())
        }
    }

    @Test
    fun `a value the daemon would ignore refuses the write and leaves splice toml untouched`() {
        serve { patch, config ->
            val reply = patch(ROUTE, """{"maxInflight":"many"}""")
            assertEquals(400, reply.status.value)
            val body = json(reply.bodyAsText())
            assertTrue(body.getValue("rejected").jsonObject.containsKey("maxInflight"))
            assertEquals(TOML, Files.readString(file))
            assertEquals(config.getConfig().maxInflight, config.getConfig("local").maxInflight)
        }
    }

    @Test
    fun `an unknown key and an unknown command are refused by name`() {
        serve { patch, _ ->
            val unknownKey = json(patch(ROUTE, """{"noSuchKnob":"1"}""").bodyAsText())
            val why = unknownKey.getValue("rejected").jsonObject.getValue("noSuchKnob").jsonPrimitive.content
            assertEquals("unknown key", why)
            assertEquals(400, patch("/api/config?head=ghost", """{"maxInflight":"3"}""").status.value)
            assertEquals(TOML, Files.readString(file))
        }
    }
}
