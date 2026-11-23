package splice.client.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.mcp.McpImportSource
import splice.core.mcp.McpRegistryRecord
import splice.core.mcp.McpUserRegistry
import java.nio.file.Files
import java.nio.file.Path

class McpRegistryLaunchTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `launch planner and overlay include imported registry servers without mutating claude json`() {
        val home = tmp.resolve("home")
        val state = tmp.resolve("state")
        val repo = tmp.resolve("repo")
        Files.createDirectories(home.resolve(".claude"))
        Files.createDirectories(repo)

        val paths = StatePaths(baseOverride = state, homeDir = home)
        val registry = McpUserRegistry(paths)
        registry.merge(
            listOf(
                McpRegistryRecord(
                    name = "codex-docs",
                    entry = buildJsonObject {
                        put("type", "http")
                        put("url", "https://developers.openai.com/mcp")
                    },
                    source = McpImportSource.CODEX,
                ),
                McpRegistryRecord(
                    name = "codex-stdio",
                    entry = buildJsonObject { put("command", "uvx") },
                    source = McpImportSource.CODEX,
                ),
            ),
        )

        val planner = McpLaunchPlanner(home, registry = registry)
        val plan = planner.plan(home.resolve(".claude"), repo)
        assertEquals(setOf("codex-docs", "codex-stdio"), plan.servers.map { it.name }.toSet())

        val sharing = McpSharing(
            enabled = true,
            exclude = emptySet(),
            endpointPrefix = "http://127.0.0.1:3096/mcp/",
            bearer = { "MCP-KEY" },
            isDirectory = DirectoryProbe { false },
        )
        val overlayFile = HostedMcpOverlay(home, sharing, state, { }, registry = registry).file("claude")
        assertNotNull(overlayFile)
        val written = Json.parseToJsonElement(Files.readString(checkNotNull(overlayFile)))
            .jsonObject.getValue("mcpServers").jsonObject
        assertTrue("codex-docs" in written, "imported http server reaches Claude overlay")
        assertTrue("codex-stdio" in written, "imported stdio server reaches Claude overlay as hosted endpoint")
        assertTrue(!Files.exists(home.resolve(".claude.json")), "user ~/.claude.json must stay untouched")
    }

    @Test
    fun `header regeneration also updates agy serverUrl entries`() {
        val state = tmp.resolve("state")
        val backupDir = state.resolve("backups")
        val agyFile = tmp.resolve("mcp_config.json")
        Files.createDirectories(state)
        Files.writeString(
            agyFile,
            buildJsonObject {
                putJsonObject("mcpServers") {
                    putJsonObject("ast-grep") {
                        put("serverUrl", "http://127.0.0.1:3096/mcp/ast-grep")
                        putJsonObject("headers") {
                            put("Authorization", "Bearer OLD-TOKEN")
                            put("X-Splice-Head", "agy")
                        }
                    }
                }
            }.toString(),
        )
        val regen = McpHeaderRegeneration("OLD-TOKEN", setOf("http://127.0.0.1:3096/mcp/"), backupDir)
        val update = regen.prepare(agyFile)
        assertEquals(1, update.matchedHeaders)
        update.publish("NEW-TOKEN")
        val after = Files.readString(agyFile)
        assertTrue("Bearer NEW-TOKEN" in after, after)
        assertTrue("OLD-TOKEN" !in after, after)
    }
}
