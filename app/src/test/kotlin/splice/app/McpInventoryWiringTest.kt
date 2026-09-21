// NEW: V4-146 — the production wiring end to end: real McpGlobalRead + real McpSharing + the five
// real readers, over a temp home (never the operator's real files). Proves the app-side assembly
// agrees with the unit-level classification McpInventoryTest already covers.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpDisposition
import splice.client.mcp.McpSharing
import splice.client.mcp.McpSourceKind
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class McpInventoryWiringTest {

    private fun sharing() = McpSharing(
        enabled = true,
        exclude = emptySet(),
        endpointPrefix = "http://127.0.0.1:1/mcp/",
        bearer = { "k" },
        isDirectory = DirectoryProbe { false },
    )

    @Test
    fun `production wiring migrates the canonical home's own server, real filesystem roots`(@TempDir home: Path) {
        home.resolve(".claude.json").writeText("""{"mcpServers":{"exa":{"command":"npx"}}}""")
        val report = McpInventoryWiring(home, sharing(), McpGlobalRead(home)).inventory.census()
        val d = report.dispositioned.single { it.registration.name == "exa" }
        assertEquals(McpDisposition.MIGRATED, d.disposition)
        assertEquals(McpSourceKind.entries.toSet(), report.kinds.map { it.kind }.toSet())
    }

    @Test
    fun `production wiring also sees a plugin-declared server, excluded with a written reason`(@TempDir home: Path) {
        home.resolve(".claude.json").writeText("""{"mcpServers":{}}""")
        val plugin = home.resolve(".claude").resolve("plugins").resolve("cache").resolve("mp")
            .resolve("desktop-commander").resolve("1.0.0").resolve(".claude-plugin")
        plugin.createDirectories()
        plugin.resolve("plugin.json").writeText(
            """{"name":"desktop-commander","mcpServers":{"desktop-commander":{"command":"npx","args":["-y","x"]}}}""",
        )
        val report = McpInventoryWiring(home, sharing(), McpGlobalRead(home)).inventory.census()
        val d = report.dispositioned.single { it.registration.name == "desktop-commander" }
        assertEquals(McpDisposition.EXCLUDED, d.disposition)
        assertTrue(d.reason.contains("plugin"), d.reason)
    }
}
