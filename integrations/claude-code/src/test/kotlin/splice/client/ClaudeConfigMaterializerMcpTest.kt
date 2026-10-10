package splice.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpRewrite
import splice.client.mcp.McpSharing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** v0.4.0 shared MCP hosting: the materializer's rewrite seam (FEATURES.md §8). */
class ClaudeConfigMaterializerMcpTest {

    private val policy = ClaudePolicy(share = setOf("settings", "mcps"), isolate = emptySet())

    private fun spec(dir: Path) = MaterializeSpec(
        dir,
        policy,
        listOf("gpt-5.6-sol"),
        "gpt-5.6-sol",
        buildJsonObject { put("cache", "x") },
        "\"/usr/bin/curl\" -s :3096/statusline",
    )

    private fun seed(home: Path) {
        Files.createDirectories(home.resolve(".claude"))
        home.resolve(".claude").resolve("settings.json").writeText("""{"theme":"dark"}""")
        home.resolve(".claude.json").writeText("""{"mcpServers":{"fs":{"command":"x"},"web":{"command":"y"}}}""")
    }

    private fun servers(dir: Path) =
        Json.parseToJsonElement(dir.resolve(".claude.json").readText()).jsonObject["mcpServers"]!!.jsonObject

    @Test
    fun `the rewrite seam replaces the inherited servers and the count still reports the operator's`(
        @TempDir home: Path,
    ) {
        seed(home)
        val dir = home.resolve(".claude-codex")
        val rewrite = McpRewrite { buildJsonObject { put("fs", buildJsonObject { put("type", "http") }) } }
        val result = ClaudeConfigMaterializer(home, mcpRewrite = rewrite).materialize(spec(dir))
        assertEquals(2, result.mcpServers)
        assertEquals(setOf("fs"), servers(dir).keys)
        assertEquals("http", servers(dir)["fs"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the real sharing rewrite points an eligible stdio entry at the host and leaves the operator's file alone`(
        @TempDir home: Path,
    ) {
        Files.createDirectories(home.resolve(".claude"))
        home.resolve(".claude").resolve("settings.json").writeText("""{"theme":"dark"}""")
        val source = home.resolve(".claude.json")
        source.writeText(
            """{"mcpServers":{"fs":{"command":"npx","args":["-y","some-mcp"]},""" +
                """"remote":{"type":"http","url":"https://x/mcp"}}}""",
        )
        val before = Files.readAllBytes(source)
        val sharing = McpSharing(true, emptySet(), "http://127.0.0.1:3096/mcp/", { "scoped" }, DirectoryProbe { false })
        val dir = home.resolve(".claude-codex")
        ClaudeConfigMaterializer(home, mcpRewrite = sharing.rewrite()).materialize(spec(dir))
        val fs = servers(dir)["fs"]!!.jsonObject
        assertEquals("http", fs["type"]?.jsonPrimitive?.content)
        assertEquals("http://127.0.0.1:3096/mcp/fs", fs["url"]?.jsonPrimitive?.content)
        assertEquals("Bearer scoped", fs["headers"]!!.jsonObject["Authorization"]?.jsonPrimitive?.content)
        assertEquals(
            Json.parseToJsonElement("""{"type":"http","url":"https://x/mcp"}"""),
            servers(dir)["remote"],
        )
        assertTrue(before.contentEquals(Files.readAllBytes(source)), "the operator file is untouched")
    }

    @Test
    fun `without the seam the servers are inherited byte for byte`(@TempDir home: Path) {
        seed(home)
        val dir = home.resolve(".claude-codex")
        ClaudeConfigMaterializer(home).materialize(spec(dir))
        assertEquals(setOf("fs", "web"), servers(dir).keys)
        assertEquals("x", servers(dir)["fs"]!!.jsonObject["command"]?.jsonPrimitive?.content)
    }
}
