// NEW: V4-315 — FEATURES.md 2.3 documents splice.toml, and its example is a file the daemon's own
// loader parses, so the doc cannot describe a shape the daemon refuses. It did: a top-level [claude]
// with isolate and config_dir, rates on a provider, a head's claude with share (found by V4-312).
// The doc is a declared input of this task (build.gradle.kts), so a doc-only edit re-runs it rather
// than serving the last green as UP-TO-DATE.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    /** The one toml block in FEATURES.md section 2.3. */
    private fun example(): String {
        val doc = Path.of(checkNotNull(System.getProperty("splice.featuresDoc")) { "splice.featuresDoc is not set" })
        val section = doc.readText().substringAfter("\n### 2.3 ").substringBefore("\n### 2.4 ")
        val blocks = TOML_BLOCK.findAll(section).map { it.groupValues[1] }.toList()
        check(blocks.size == 1) { "FEATURES.md 2.3 holds ${blocks.size} toml blocks, not one" }
        return blocks.single()
    }
}
