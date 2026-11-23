package splice.provider.antigravity

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.mcp.McpImportSource
import splice.core.mcp.McpRegistryRecord
import splice.core.mcp.McpUserRegistry
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class AgyMcpRegistryTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `ClaudeMcpFiles loads Splice registry servers with claude json taking precedence`() {
        val home = tmp.resolve("home")
        val state = tmp.resolve("state")
        val cwd = tmp.resolve("repo")
        Files.createDirectories(home)
        Files.createDirectories(cwd)

        val registry = McpUserRegistry(StatePaths(baseOverride = state, homeDir = home))
        registry.merge(
            listOf(
                McpRegistryRecord(
                    name = "codex-tool",
                    entry = buildJsonObject {
                        put("type", "http")
                        put("url", "https://codex.example/mcp")
                    },
                    source = McpImportSource.CODEX,
                ),
                McpRegistryRecord(
                    name = "shared",
                    entry = buildJsonObject {
                        put("type", "http")
                        put("url", "https://registry.example/mcp")
                    },
                    source = McpImportSource.CODEX,
                ),
            ),
        )
        Files.writeString(
            home.resolve(".claude.json"),
            """{"mcpServers":{"shared":{"type":"http","url":"https://claude.example/mcp"}}}""",
        )

        val env = EnvReader { key -> if (key == "HOME") home.toString() else null }
        val loaded = ClaudeMcpFiles(cwd, env, {}, registry).load(strictMcpConfig = false)
            .associateBy(AgyMcpSpec::name)

        assertEquals(setOf("codex-tool", "shared"), loaded.keys)
        assertEquals("https://codex.example/mcp", (loaded["codex-tool"] as AgyMcpSpec.Remote).url)
        assertEquals("https://claude.example/mcp", (loaded["shared"] as AgyMcpSpec.Remote).url)
    }
}
