// NEW: V4-146 — one reader per source kind, proven against fixtures under a @TempDir (never the
// operator's real files, per the campaign packet). Every "found nothing" assertion here is paired
// with a fixture that WOULD have been found by a different kind or a decoy path, so a reader that
// silently returns empty regardless of input — this row's own failure mode, one level down — fails
// these tests rather than passing vacuously.
package mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.launch.GlobalMcpServersReader
import splice.core.launch.PluginInlineReader
import splice.core.launch.PluginMcpJsonReader
import splice.core.launch.ProjectMcpServersReader
import splice.core.launch.RepoMcpJsonReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class McpSourcesTest {

    @Test
    fun `GLOBAL reads every home's own top-level mcpServers, not only the vanilla one`(@TempDir home: Path) {
        writeFixtures(home)
        val scan = GlobalMcpServersReader(home)()
        assertTrue(scan.rootsScanned.size >= 2, "expected the vanilla home plus .claude-work: ${scan.rootsScanned}")
        assertEquals(setOf("exa", "work-only"), scan.registrations.map { it.name }.toSet())
    }

    @Test
    fun `PROJECT reads projects dot path dot mcpServers nested inside each home's own file`(@TempDir home: Path) {
        writeFixtures(home)
        val scan = ProjectMcpServersReader(home)()
        assertEquals(setOf("local-mcp"), scan.registrations.map { it.name }.toSet())
        assertEquals(home.resolve("proj-b"), scan.registrations.single().scope)
    }

    @Test
    fun `REPO checks every project path Claude Code already knows about, not a hand list`(@TempDir home: Path) {
        writeFixtures(home)
        val scan = RepoMcpJsonReader(home)()
        assertEquals(setOf("repo-tool"), scan.registrations.map { it.name }.toSet())
        assertTrue(scan.rootsScanned.contains(home.resolve("proj-a")))
        assertTrue(
            scan.rootsScanned.contains(home.resolve("proj-b")),
            "proj-b is a known project (it has its own PROJECT override) even though it carries no .mcp.json",
        )
    }

    @Test
    fun `PLUGIN_MCP_JSON finds a plugin's own manifest and never a file inside dot git`(@TempDir home: Path) {
        writeFixtures(home)
        val scan = PluginMcpJsonReader(home)()
        val names = scan.registrations.map { it.name }.toSet()
        assertEquals(setOf("plugin-tool"), names, "must exclude the .git decoy 'git-ghost'")
    }

    @Test
    fun `PLUGIN_INLINE matches only dot-claude-plugin, not a sibling agent's manifest dir`(@TempDir home: Path) {
        writeFixtures(home)
        val scan = PluginInlineReader(home)()
        val names = scan.registrations.map { it.name }.toSet()
        assertEquals(setOf("inline-tool"), names, "must exclude the .codex-plugin decoy 'codex-only'")
    }

    @Test
    fun `every reader is honestly empty on an untouched dir, then non-empty once fixtures exist`(@TempDir home: Path) {
        val untouched = listOf(
            GlobalMcpServersReader(home)(),
            ProjectMcpServersReader(home)(),
            RepoMcpJsonReader(home)(),
            PluginMcpJsonReader(home)(),
            PluginInlineReader(home)(),
        )
        untouched.forEach { scan -> assertTrue(scan.registrations.isEmpty(), "${scan.kind} on an empty dir") }
        writeFixtures(home)
        listOf(
            GlobalMcpServersReader(home)(),
            ProjectMcpServersReader(home)(),
            RepoMcpJsonReader(home)(),
            PluginMcpJsonReader(home)(),
            PluginInlineReader(home)(),
        ).forEach { scan ->
            assertTrue(scan.registrations.isNotEmpty(), "${scan.kind} found nothing with fixtures present")
        }
    }

    @Test
    fun `a plugins dir shared by a symlink is counted once, not once per home that links to it`(@TempDir home: Path) {
        val realPlugins = home.resolve(".claude").resolve("plugins")
        realPlugins.resolve("cache/mp/plug1/1.0.0").createDirectories()
        realPlugins.resolve("cache/mp/plug1/1.0.0/.mcp.json")
            .writeText("""{"mcpServers":{"shared-tool":{"command":"npx"}}}""")
        home.resolve(".claude-work").createDirectories()
        Files.createSymbolicLink(home.resolve(".claude-work").resolve("plugins"), realPlugins)

        val scan = PluginMcpJsonReader(home)()
        assertEquals(1, scan.rootsScanned.size, scan.rootsScanned.toString())
        assertEquals(1, scan.registrations.size, "a symlinked plugins tree must be counted once, not per linking home")
    }

    private fun writeFixtures(home: Path) {
        val projA = home.resolve("proj-a")
        val projB = home.resolve("proj-b")
        projA.createDirectories()
        projB.createDirectories()
        home.resolve(".claude.json").writeText(
            """
            {
              "mcpServers": {"exa": {"command": "npx", "args": ["-y", "exa-mcp"]}},
              "projects": {
                "$projA": {},
                "$projB": {"mcpServers": {"local-mcp": {"command": "node"}}}
              }
            }
            """.trimIndent(),
        )
        projA.resolve(".mcp.json").writeText(
            """{"mcpServers":{"repo-tool":{"command":"npx","args":["-y","repo-tool"]}}}""",
        )

        val workHome = home.resolve(".claude-work")
        workHome.createDirectories()
        workHome.resolve(".claude.json").writeText("""{"mcpServers":{"work-only":{"command":"npx"}}}""")

        val marketplace = home.resolve(".claude").resolve("plugins").resolve("cache").resolve("mp")
        val plug1 = marketplace.resolve("plug1").resolve("1.0.0")
        plug1.createDirectories()
        plug1.resolve(".mcp.json").writeText("""{"mcpServers":{"plugin-tool":{"command":"npx"}}}""")

        val plug2 = marketplace.resolve("plug2").resolve("1.0.0")
        val claudePluginDir = plug2.resolve(".claude-plugin")
        claudePluginDir.createDirectories()
        claudePluginDir.resolve("plugin.json")
            .writeText("""{"name":"plug2","mcpServers":{"inline-tool":{"command":"npx"}}}""")
        // Decoy: a different agent's own manifest dir beside .claude-plugin — must never be read.
        val codexPluginDir = plug2.resolve(".codex-plugin")
        codexPluginDir.createDirectories()
        codexPluginDir.resolve("plugin.json")
            .writeText("""{"name":"plug2","mcpServers":{"codex-only":{"command":"npx"}}}""")

        // Decoy: a file that LOOKS like a plugin manifest but sits inside a .git clone — must never
        // be counted as a real plugin's declaration.
        val gitDecoy = home.resolve(".claude").resolve("plugins").resolve("marketplaces").resolve("mp")
            .resolve(".git").resolve("objects").resolve("aa")
        gitDecoy.createDirectories()
        gitDecoy.resolve(".mcp.json").writeText("""{"mcpServers":{"git-ghost":{"command":"npx"}}}""")
    }
}
