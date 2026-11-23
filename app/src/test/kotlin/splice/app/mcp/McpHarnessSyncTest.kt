package splice.app.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.mcp.McpHarnessCli
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpImportSource
import splice.core.mcp.McpUserRegistry
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class McpHarnessSyncTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `discovery reads claude, agy, and codex while skipping disabled and circular splice entries`() {
        val home = seedDiscoveryFixtures(tmp.resolve("home"))
        val discovery = McpHarnessDiscovery(home, EnvReader { null }, osName = "Linux")
        val found = discovery.discover().associateBy { "${it.source.wire}:${it.name}" }

        assertTrue("claude-code:eli-brain" in found)
        assertTrue("claude-code:ast-grep" in found)
        assertTrue("agy:torad-fleet" in found)
        assertFalse("agy:off-server" in found)
        assertFalse("agy:loopback-hosted" in found)
        assertTrue("codex:hotswan" in found)
        assertTrue("codex:docs" in found)
        assertFalse("codex:disabled_one" in found)
        assertEquals(setOf(McpHarness.CLAUDE, McpHarness.AGY, McpHarness.CODEX), discovery.detectedHarnesses())
    }

    @Test
    fun `codex toml codec preserves unrelated sections and per-server restrictions`() {
        val codec = CodexMcpTomlCodec()
        val original = """
            model = "gpt-5.4"

            [mcp_servers.ast-grep]
            command = "old-cmd"
            tool_timeout_sec = 45

            [mcp_servers.keep_me]
            url = "https://keep.example/mcp"
        """.trimIndent() + "\n"

        val updated = codec.upsert(
            original,
            mapOf(
                "ast-grep" to buildJsonObject {
                    put("url", "http://127.0.0.1:3096/mcp/ast-grep")
                    putJsonObject("http_headers") {
                        put("Authorization", "Bearer TOKEN")
                        put("X-Splice-Head", "codex")
                    }
                },
            ),
        )
        assertTrue("""model = "gpt-5.4"""" in updated, updated)
        assertTrue("[mcp_servers.keep_me]" in updated, updated)
        assertTrue("tool_timeout_sec = 45" in updated, updated)
        assertTrue("""url = "http://127.0.0.1:3096/mcp/ast-grep"""" in updated, updated)
        assertFalse("old-cmd" in updated, updated)
    }

    @Test
    fun `sync writes hosted splice endpoints and http servers to agy and codex while preserving existing entries`() =
        runBlocking {
            val home = seedSyncFixtures(tmp.resolve("home"))
            val state = tmp.resolve("state")
            val paths = StatePaths(baseOverride = state, homeDir = home)
            val env = EnvReader { key -> if (key == "SPLICE_STATE_DIR") state.toString() else null }
            val seams = McpHarnessSyncSeams(
                osName = "Linux",
                frontReachable = LoopbackPortProbe { false },
                codexRpc = CodexRpcSync { _, _ -> "rpc unavailable in test" },
                bearerOverride = { "TEST-MCP-BEARER" },
            )

            val outLines = mutableListOf<String>()
            val errLines = mutableListOf<String>()
            val cli = McpHarnessCli(
                out = TerminalOutput { outLines += it },
                err = TerminalOutput { errLines += it },
                env = env,
                homeOverride = home,
                seams = seams,
            )

            val exit = cli.run(listOf("sync", "--import", "--to", "all"))
            assertEquals(0, exit, "stderr=$errLines stdout=$outLines")
            assertSyncedConfigs(home, paths)
            assertStatusRedactsSecrets(home, env, seams)
        }

    private fun seedDiscoveryFixtures(home: Path): Path {
        Files.createDirectories(home.resolve(".gemini").resolve("config"))
        Files.createDirectories(home.resolve(".codex"))
        Files.writeString(
            home.resolve(".claude.json"),
            """{"mcpServers":{"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp",""" +
                """"headers":{"Authorization":"Bearer B"}},"ast-grep":{"command":"uvx","args":["ast-grep-server"]}}}""",
        )
        Files.writeString(
            home.resolve(".gemini").resolve("config").resolve("mcp_config.json"),
            """{"mcpServers":{"torad-fleet":{"command":"node","args":["/opt/fleet.mjs"],"disabled":false},""" +
                """"off-server":{"serverUrl":"https://off.example/mcp","disabled":true},""" +
                """"loopback-hosted":{"serverUrl":"http://127.0.0.1:3096/mcp/ast-grep",""" +
                """"headers":{"Authorization":"Bearer K","X-Splice-Head":"agy"}}}}""",
        )
        Files.writeString(
            home.resolve(".codex").resolve("config.toml"),
            """
            model = "gpt-5"
            [mcp_servers.hotswan]
            command = "node"
            args = ["/opt/hotswan.js"]
            [mcp_servers.docs]
            url = "https://developers.openai.com/mcp"
            http_headers = { Authorization = "Bearer D" }
            [mcp_servers.disabled_one]
            enabled = false
            url = "https://nope.example/mcp"
            """.trimIndent(),
        )
        return home
    }

    private fun seedSyncFixtures(home: Path): Path {
        Files.createDirectories(home.resolve(".gemini").resolve("config"))
        Files.createDirectories(home.resolve(".codex"))
        Files.writeString(
            home.resolve(".claude.json"),
            """
            {
              "mcpServers": {
                "eli-brain": {
                  "type": "http",
                  "url": "https://mcp.torad.ai/mcp",
                  "headers": {"Authorization": "Bearer BRAIN"}
                },
                "ast-grep": {"command": "uvx", "args": ["ast-grep-server"]}
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            home.resolve(".gemini").resolve("config").resolve("mcp_config.json"),
            """{"mcpServers":{"ide-only":{"command":"node","args":["ide.js"]}}}""",
        )
        Files.writeString(
            home.resolve(".codex").resolve("config.toml"),
            """
            model = "o3"

            [mcp_servers.codex-only]
            url = "https://codex-only.example/mcp"
            """.trimIndent(),
        )
        return home
    }

    private fun assertSyncedConfigs(home: Path, paths: StatePaths) {
        val agyDoc = Json.parseToJsonElement(
            Files.readString(home.resolve(".gemini").resolve("config").resolve("mcp_config.json")),
        ).jsonObject.getValue("mcpServers").jsonObject
        assertTrue("ide-only" in agyDoc, "existing agy server preserved")
        val eliUrl = agyDoc.getValue("eli-brain").jsonObject.getValue("serverUrl").jsonPrimitive.content
        assertEquals("https://mcp.torad.ai/mcp", eliUrl)
        val astGrep = agyDoc.getValue("ast-grep").jsonObject
        assertEquals("http://127.0.0.1:3096/mcp/ast-grep", astGrep.getValue("serverUrl").jsonPrimitive.content)
        val astAuth = astGrep.getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content
        assertEquals("Bearer TEST-MCP-BEARER", astAuth)

        val codexText = Files.readString(home.resolve(".codex").resolve("config.toml"))
        assertTrue("""model = "o3"""" in codexText, codexText)
        assertTrue("[mcp_servers.codex-only]" in codexText, codexText)
        assertTrue("""url = "http://127.0.0.1:3096/mcp/ast-grep"""" in codexText, codexText)
        assertTrue("""url = "https://mcp.torad.ai/mcp"""" in codexText, codexText)

        val snapshot = McpUserRegistry(paths).read()
        assertEquals(setOf(McpHarness.CLAUDE, McpHarness.AGY, McpHarness.CODEX), snapshot.harnesses)
        assertTrue("codex-only" in snapshot.servers, "codex-only was imported into Splice MCP")
        assertEquals(McpImportSource.CODEX, snapshot.servers.getValue("codex-only").source)
    }

    private suspend fun assertStatusRedactsSecrets(home: Path, env: EnvReader, seams: McpHarnessSyncSeams) {
        val statusLines = mutableListOf<String>()
        val statusCli = McpHarnessCli(
            out = TerminalOutput { statusLines += it },
            err = TerminalOutput {},
            env = env,
            homeOverride = home,
            seams = seams,
        )
        assertEquals(0, statusCli.run(listOf("status", "--json")))
        val joined = statusLines.joinToString("\n")
        val statusJson = Json.parseToJsonElement(joined).jsonObject
        assertTrue(statusJson["connected_harnesses"].toString().contains("agy"))
        assertFalse(joined.contains("TEST-MCP-BEARER"))
        assertFalse(joined.contains("Bearer BRAIN"))
    }
}
