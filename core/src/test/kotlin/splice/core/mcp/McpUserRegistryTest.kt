package splice.core.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class McpUserRegistryTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `missing or malformed registry reads as empty`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"), homeDir = tmp)
        val registry = McpUserRegistry(paths)
        assertTrue(registry.read().servers.isEmpty())
        assertTrue(registry.read().harnesses.isEmpty())

        Files.createDirectories(registry.file.parent)
        Files.writeString(registry.file, "{not-valid-json")
        assertTrue(registry.read().servers.isEmpty())

        val rec = McpRegistryRecord(
            name = "new-srv",
            entry = buildJsonObject { put("command", "node") },
            source = McpImportSource.AGY,
        )
        assertThrows(IOException::class.java) { registry.merge(listOf(rec)) }
        assertThrows(IOException::class.java) { registry.rememberHarnesses(setOf(McpHarness.AGY)) }
        assertEquals("{not-valid-json", Files.readString(registry.file))
    }

    @Test
    fun `merge persists owner-only registry and writes backup on overwrite`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"), homeDir = tmp)
        val registry = McpUserRegistry(paths)
        val first = McpRegistryRecord(
            name = "eli-brain",
            entry = buildJsonObject {
                put("type", "http")
                put("url", "https://mcp.torad.ai/mcp")
            },
            source = McpImportSource.CLAUDE_CODE,
            importedAt = "2026-11-22T00:00:00Z",
        )
        registry.merge(listOf(first), setOf(McpHarness.CLAUDE, McpHarness.AGY))
        assertTrue(Files.isRegularFile(registry.file))
        if ("posix" in registry.file.fileSystem.supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(registry.file)))
        }

        val second = McpRegistryRecord(
            name = "ast-grep",
            entry = buildJsonObject { put("command", "uvx") },
            source = McpImportSource.CODEX,
        )
        val updated = registry.merge(listOf(second), setOf(McpHarness.CODEX))
        assertEquals(setOf("eli-brain", "ast-grep"), updated.servers.keys)
        assertEquals(setOf(McpHarness.CLAUDE, McpHarness.AGY, McpHarness.CODEX), updated.harnesses)

        val backups = paths.stateDir.resolve("mcp-registry-backups")
        assertTrue(Files.isDirectory(backups))
        assertEquals(1, Files.list(backups).use { it.count() })
    }

    @Test
    fun `redactUrl strips userinfo, query strings and fragments while preserving host and path`() {
        val names = McpRegistryNames()
        assertEquals(
            "https://mcp.exa.ai/mcp",
            names.redactUrl("https://mcp.exa.ai/mcp?exaApiKey=secret-token#frag"),
        )
        assertEquals(
            "https://mcp.torad.ai:8443/mcp",
            names.redactUrl("https://user:pass@mcp.torad.ai:8443/mcp?token=abc#h"),
        )
        assertEquals("https://mcp.torad.ai/mcp", names.redactUrl("https://mcp.torad.ai/mcp"))
        assertNull(names.redactUrl(null))
        assertEquals(McpHarness.AGY, names.harness("antigravity"))
        assertEquals(McpImportSource.CODEX, names.source("codex"))
    }
}
