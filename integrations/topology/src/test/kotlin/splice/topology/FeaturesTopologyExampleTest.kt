// NEW: V4-315 — FEATURES.md 2.3 documents splice.toml, and its example is a file the daemon's own
// loader parses, so the doc cannot describe a shape the daemon refuses. It did: a top-level [claude]
// with isolate and config_dir, rates on a provider, a head's claude with share (found by V4-312).
// The doc is a declared input of this task (build.gradle.kts), so a doc-only edit re-runs it rather
// than serving the last green as UP-TO-DATE.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/** A fenced toml block, its body captured. */
private val TOML_BLOCK = Regex("```toml\n(.*?)\n```", RegexOption.DOT_MATCHES_ALL)

class FeaturesTopologyExampleTest {

    @Test
    fun `FEATURES 2_3's splice_toml example is a file the loader parses - V4-315`() {
        val topology = TopologyLoader.parse(example())
        assertEquals(listOf("settings", "mcps"), topology.claude.share)
        val head = topology.heads.getValue("claudex")
        assertEquals("~/.config/splice/claude", head.claude.configDir, "a head's [claude] carries config_dir")
        assertEquals(listOf("projects"), head.claude.isolate)
        assertNotNull(topology.providers.getValue("codex").models.single().rates, "a model row carries its rates")
        assertNotNull(topology.projects["/home/me/app"]?.systemPrompt, "[projects] holds a repo's standing prompt")
    }

    @Test
    fun `release documentation snippets parse with real topology keys`() {
        val root = Path.of(checkNotNull(System.getProperty("splice.releaseDocs")))
        listOf("README.md", "CHANGELOG.md").forEach { name ->
            assertTrue(DocumentationTopologySnippets.checkDocument(root.resolve(name).readText()) > 0, name)
        }
        assertTrue(
            DocumentationTopologySnippets.checkExample(
                root.resolve("app/src/main/resources/splice.example.toml").readText(),
            ) > 0,
        )
    }

    @Test
    fun `snippet census refuses bad types and unknown keys without filtering them out`() {
        listOf(
            "[defaults] messageEdges = false",
            "[defaults] mcp_max_servers = \"64\"",
            "invented_setting = \"value\"",
            "[daemon] invented_setting = true",
        ).forEach { snippet ->
            assertThrows(
                Exception::class.java,
                { DocumentationTopologySnippets.checkDocument("```toml\n$snippet\n```") },
                snippet,
            )
        }
        val head = """
            [heads.example]
            provider = "example"
            port = 3105
            discovery_prefix = "claude-example--"
            pinned_model = "example-model"
            [heads.example.overrides]
            trace = "false"
        """.trimIndent()
        DocumentationTopologySnippets.checkKnobs(head)
        assertThrows(Exception::class.java) {
            DocumentationTopologySnippets.checkKnobs(head.replace("trace = \"false\"", "trace = false"))
        }
        assertEquals(
            1,
            DocumentationTopologySnippets.checkDocument("```toml\n[defaults]\nmcpMaxServers = \"64\"\n```"),
        )
        assertThrows(Exception::class.java) {
            DocumentationTopologySnippets.checkDocument("```toml\n[defaults]\nunlisted = \"1\"\n```")
        }
    }

    @Test
    fun `indented fences array tables and optional example blocks are checked`() {
        assertEquals(
            1,
            DocumentationTopologySnippets.checkDocument(
                "   ```toml\n[daemon]\nmcp_hosting = false\n   ```",
            ),
        )
        assertThrows(Exception::class.java) {
            DocumentationTopologySnippets.checkDocument("   ```toml\n[daemon]\nunknown = true\n   ```")
        }
        assertEquals(
            1,
            DocumentationTopologySnippets.checkDocument(
                "```toml\n[[compaction.model]]\nmodel = \"example\"\ninstructions = \"Keep state.\"\n```",
            ),
        )
        assertThrows(Exception::class.java) {
            DocumentationTopologySnippets.checkExample("# [daemon]\n# unknown = true\n")
        }
    }

    /** The one toml block in FEATURES.md section 2.3. */
    private fun example(): String {
        val doc = Path.of(checkNotNull(System.getProperty("splice.featuresDoc")) { "splice.featuresDoc is not set" })
        val section = doc.readText().substringAfter("\n### 2.3 ").substringBefore("\n### 2.4 ")
        val blocks = TOML_BLOCK.findAll(section).map { it.groupValues[1] }.toList()
        check(blocks.size == 1) { "FEATURES.md 2.3 holds ${blocks.size} toml blocks, not one" }
        return blocks.single()
    }
}
