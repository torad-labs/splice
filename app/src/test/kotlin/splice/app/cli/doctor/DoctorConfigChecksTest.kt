// NEW: V4-124 — the doctor rows for the per-project prompt layers, and the TOML shape they read.
// The shape is parsed by the SAME TopologyLoader the daemon uses, so a quoted root key and the
// nested per-head table are proven to decode, not assumed to.
package splice.app.cli.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.prompt.SystemPromptMode
import splice.topology.TopologyLoader
import java.nio.file.Path
import java.nio.file.Paths

class DoctorConfigChecksTest {

    @Test
    fun `a projects table with a per-head layer parses from TOML`(@TempDir tmp: Path) {
        val topology = TopologyLoader.parse(toml(tmp, projectMode = "\"replace\""))
        val project = topology.projects.values.single()

        assertEquals(tmp.toString(), topology.projects.keys.single().trim('"'))
        assertEquals("be careful here", project.systemPrompt)
        assertEquals(SystemPromptMode.REPLACE, project.systemPromptMode)
        assertEquals(".splice/prompt.md", project.heads.getValue("one").systemPromptFile)
    }

    @Test
    fun `every project layer gets a row naming its root, mode and source`(@TempDir tmp: Path) {
        val rows = projectRows(toml(tmp, projectMode = null))

        val names = listOf("project-prompt:project:$tmp", "project-prompt:project-head:$tmp:one")
        assertEquals(names, rows.map { it.name })
        assertTrue(rows.all { it.status == CheckStatus.OK }, rows.toString())
        assertEquals("project:$tmp append (inline)", rows[0].detail)
        assertEquals("project-head:$tmp:one append (file:.splice/prompt.md)", rows[1].detail)
    }

    @Test
    fun `a replace layer warns that the client instructions and earlier layers are substituted`(@TempDir tmp: Path) {
        val row = projectRows(toml(tmp, projectMode = "\"replace\"")).first()

        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("every earlier layer"), row.detail)
        assertTrue(row.detail.contains("bare model with tools attached"), row.detail)
        assertTrue(requireNotNull(row.fix).contains("system_prompt_mode = \"append\""), row.fix.orEmpty())
    }

    /** V4-170: the strip mode decodes from TOML through the daemon's own loader. V4-171: it WARNs
     *  like replace — the client's instructions are edited, and what a pattern removes is the
     *  operator's to own. */
    @Test
    fun `a strip layer parses from TOML and warns that the client field is edited`(@TempDir tmp: Path) {
        val topology = TopologyLoader.parse(toml(tmp, projectMode = "\"strip\""))
        val row = projectRows(toml(tmp, projectMode = "\"strip\"")).first()

        assertEquals(SystemPromptMode.STRIP, topology.projects.values.single().systemPromptMode)
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("system_prompt_mode = \"strip\" (inline)"), row.detail)
        assertTrue(row.detail.contains("yours to own"), row.detail)
        // V4-172: the STRIP remedy, not the REPLACE one. A strip layer's value is a regex list, so
        // "set system_prompt_mode = append instead" would ship the patterns upstream as prompt text.
        val fix = requireNotNull(row.fix)
        assertTrue(fix.contains("regexes, never prompt text"), fix)
        assertTrue(fix.contains("narrow the pattern list"), fix)
    }

    @Test
    fun `a root that does not exist on disk is a warning, not a failure`(@TempDir tmp: Path) {
        val gone = tmp.resolve("gone")
        val rows = projectRows(toml(gone, projectMode = null))

        assertEquals(CheckStatus.WARN, rows.first().status)
        assertTrue(rows.first().detail.contains("does not exist"), rows.first().detail)
    }

    @Test
    fun `a relative root fails, because the daemon refuses it at load`() {
        val rows = projectRows(toml(Paths.get("relative/repo"), projectMode = null))

        assertEquals(listOf(CheckStatus.FAIL), rows.map { it.status })
    }

    private fun projectRows(toml: String) = DoctorConfigChecks()
        .configurationChecks(DoctorTopology.Parsed(TopologyLoader.parse(toml)), Paths.get("/tmp/splice.toml"))
        .filter { it.name.startsWith("project-prompt:") }

    private fun toml(root: Path, projectMode: String?): String = buildString {
        append(
            """
            [providers.cloud]
            dialect = "openai-chat"
            base_url = "https://openrouter.ai/api/v1"
            auth = { kind = "api-key", env = "K" }
            [[providers.cloud.models]]
            id = "m1"
            context_window = 8192

            [heads.one]
            provider = "cloud"
            port = 3901
            discovery_prefix = "claude-one--"
            pinned_model = "m1"

            [projects."$root"]
            system_prompt = "be careful here"

            """.trimIndent() + "\n",
        )
        if (projectMode != null) append("system_prompt_mode = $projectMode\n")
        append("\n[projects.\"$root\".heads.one]\nsystem_prompt_file = \".splice/prompt.md\"\n")
    }
}
