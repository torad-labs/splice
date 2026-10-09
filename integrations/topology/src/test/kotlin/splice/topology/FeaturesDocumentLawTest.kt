// FEATURES.md 2.3 documents splice.toml, and its example is a file the daemon's own loader parses, so the doc
// cannot describe a shape the daemon refuses. The release documents and the shipped example are checked the
// same way. The docs are declared inputs of this task (build.gradle.kts), so a doc-only edit re-runs it.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/** A fenced toml block, its body captured. */
private val TOML_BLOCK = Regex("```toml\n(.*?)\n```", RegexOption.DOT_MATCHES_ALL)

/** The laws that read the documents from disk: FEATURES.md 2.3's example and the release documents. A class of its own,
 *  because the discovery census refuses a class split across the law tag. */
@Tag("law")
class FeaturesDocumentLawTest {

    @Test
    fun `FEATURES 2_3's splice_toml example is a file the loader parses`() {
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

    /** The one toml block in FEATURES.md section 2.3. */
    private fun example(): String {
        val doc = Path.of(checkNotNull(System.getProperty("splice.featuresDoc")) { "splice.featuresDoc is not set" })
        val section = doc.readText().substringAfter("\n### 2.3 ").substringBefore("\n### 2.4 ")
        val blocks = TOML_BLOCK.findAll(section).map { it.groupValues[1] }.toList()
        check(blocks.size == 1) { "FEATURES.md 2.3 holds ${blocks.size} toml blocks, not one" }
        return blocks.single()
    }
}

