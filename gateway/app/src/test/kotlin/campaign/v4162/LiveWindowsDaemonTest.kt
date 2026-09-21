// NEW: V4-162 — the wiring pin: a real Daemon, booted from a splice.toml on disk, follows a
// context_window edit to that file with no restart, on every surface that serves a window.
//
//   /health        topologyDigest moves to the edited bytes and topologyStale stays false, so
//                  `splice doctor` finds nothing to restart for; a port edit still reads stale;
//   POST /launch   plants the edited window as CLAUDE_CODE_MAX_CONTEXT_TOKENS (the spec is
//                  boot-frozen, LaunchRoutes re-reads it per launch);
//   /api/models    the console's models page shows the edited window.
//
// Each of those has a unit test beside it; this one fails if Daemon stops ATTACHING the watcher to
// the catalog it hands the head, or stops handing ControlPlane the running digest.
package campaign.v4162

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.app.TopologyLoader
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.head.awaitListening
import splice.head.freshPort
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class LiveWindowsDaemonTest {

    @TempDir
    lateinit var tmp: Path

    private val client = HttpClient(CIO)
    private val controlPort = freshPort()
    private val headPort = freshPort()
    private val upstreamPort = freshPort()

    // local = false: the base_url is loopback, and this pin is about the wiring, not the runtime
    // question TopologyWindowsTest pins.
    private fun toml(window: Long, port: Int = headPort): String = """
        [daemon]
        control_port = $controlPort

        [providers.chat]
        dialect = "openai-chat"
        base_url = "http://127.0.0.1:$upstreamPort/v1"
        auth = { kind = "api-key", file = '${tmp.resolve("key.json")}' }
        local = false

        [[providers.chat.models]]
        id = "bonsai"
        label = "Bonsai"
        context_window = $window

        [heads.chat]
        provider = "chat"
        port = $port
        discovery_prefix = "claude-chat--"
        pinned_model = "bonsai"
        claude = { command = "claude-chat", config_dir = '${tmp.resolve(".claude-chat")}' }
    """.trimIndent()

    @Test
    fun `a context_window edit reaches health, launch and the models page with no restart`() = runBlocking {
        Files.writeString(tmp.resolve("key.json"), """{"api_key":"chat-key"}""")
        val file = tmp.resolve("splice.toml")
        val boot = toml(131_072)
        Files.writeString(file, boot)
        val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        val key = MgmtKey(statePaths).get()
        val daemon = Daemon(
            topology = TopologyLoader.parse(boot),
            statePaths = statePaths,
            dashboardHtml = { "<!doctype html>" },
            log = {},
            topologyDigest = TopologyLoader.sha256Hex(boot.toByteArray()),
            topologyPath = file,
        )
        daemon.start()
        try {
            awaitListening(controlPort, headPort)
            fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
            suspend fun health() = json(client.get("http://127.0.0.1:$controlPort/health").bodyAsText())
            suspend fun launched(): Long {
                val r = client.post("http://127.0.0.1:$controlPort/launch/chat") {
                    header("Authorization", "Bearer $key")
                }
                assertEquals(200, r.status.value, r.bodyAsText())
                return json(r.bodyAsText())["env"]!!.jsonObject["CLAUDE_CODE_MAX_CONTEXT_TOKENS"]!!
                    .jsonPrimitive.content.toLong()
            }
            suspend fun modelsPage(): Long {
                val r = client.get("http://127.0.0.1:$controlPort/api/models") {
                    header("Authorization", "Bearer $key")
                }
                assertEquals(200, r.status.value, r.bodyAsText())
                val head = json(r.bodyAsText())["heads"]!!.jsonArray.single().jsonObject
                return head["models"]!!.jsonArray.single().jsonObject["context_window"]!!.jsonPrimitive.long
            }
            assertEquals(131_072, launched())

            val edited = toml(245_760)
            Files.writeString(file, edited)
            Files.setLastModifiedTime(file, FileTime.fromMillis(2_000))

            val editedDigest = TopologyLoader.sha256Hex(edited.toByteArray())
            assertEquals(editedDigest, health()["topologyDigest"]!!.jsonPrimitive.content)
            assertFalse(health()["topologyStale"]!!.jsonPrimitive.boolean)
            assertEquals(245_760, launched())
            assertEquals(245_760, modelsPage())

            Files.writeString(file, toml(245_760, port = headPort + 1))
            Files.setLastModifiedTime(file, FileTime.fromMillis(3_000))
            assertTrue(health()["topologyStale"]!!.jsonPrimitive.boolean)
            assertEquals(editedDigest, health()["topologyDigest"]!!.jsonPrimitive.content)
        } finally {
            daemon.stop()
            client.close()
        }
    }
}
