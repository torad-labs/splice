// NEW: V4-124 — the doctor rows for the per-project prompt layers, and the TOML shape they read.
// The shape is parsed by the SAME TopologyLoader the daemon uses, so a quoted root key and the
// nested per-head table are proven to decode, not assumed to.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.prompt.SystemPromptMode
import splice.core.topology.HeadConfig
import splice.core.topology.Topology
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DoctorConfigChecksTest {

    @Test
    fun `a parsed topology with no heads is setup guidance, not a failure`() {
        val row = DoctorTestPorts.configChecks()
            .configurationChecks(DoctorTopology.Parsed(Topology()), Paths.get("/tmp/splice.toml"))
            .single { it.name == "topology" }
        assertEquals(CheckStatus.INFO, row.status)
        assertTrue(row.detail.contains("not set up yet"), row.detail)
        assertEquals("splice setup", row.fix)
    }

    // V4-109: a key the operator wrote and did not get is a WARN that names it. Nothing pinned this
    // row: deleting its call in configurationChecks left every doctor test green.
    @Test
    fun `an unknown key under defaults and an uncoercible head override each warn by name`() {
        val head = HeadConfig(
            provider = "p",
            port = 1,
            discoveryPrefix = "p--",
            pinnedModel = "m",
            overrides = mapOf("streamIdleMs" to "soon"),
        )
        val topology = Topology(defaults = mapOf("streamIdelMs" to "1000"), heads = mapOf("one" to head))

        val rows = DoctorTestPorts.configChecks()
            .configurationChecks(DoctorTopology.Parsed(topology), Paths.get("/tmp/splice.toml"))
            .filter { "is ignored" in it.detail }

        assertEquals(
            listOf(
                "setting 'streamIdelMs' is ignored (unknown key); the knob keeps its default",
                "setting 'heads.one.streamIdleMs' is ignored (not a valid number); the knob keeps its default",
            ),
            rows.map { it.detail },
        )
        assertTrue(rows.all { it.status == CheckStatus.WARN }, rows.toString())
        assertEquals("fix or remove 'streamIdelMs' in /tmp/splice.toml", rows.first().fix)
    }

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
        Files.createDirectories(tmp.resolve(".splice"))
        Files.writeString(tmp.resolve(".splice/prompt.md"), "stay terse")
        val rows = projectRows(toml(tmp, projectMode = null))

        val names = listOf("project-prompt:project:$tmp", "project-prompt:project-head:$tmp:one")
        assertEquals(names, rows.map { it.name })
        assertTrue(rows.all { it.status == CheckStatus.OK }, rows.toString())
        assertEquals("project:$tmp append (inline)", rows[0].detail)
        assertEquals("project-head:$tmp:one append (file:.splice/prompt.md)", rows[1].detail)
    }

    @Test
    fun `a prompt file the daemon cannot read fails the doctor, whatever its mode`(@TempDir tmp: Path) {
        val rows = projectRows(toml(tmp, projectMode = "\"replace\""))

        val failed = rows.single { it.status == CheckStatus.FAIL }
        assertEquals("project-prompt:project-head:$tmp:one", failed.name)
        val missing = tmp.resolve(".splice/prompt.md")
        assertTrue(failed.detail.contains("system_prompt_file is unreadable: $missing"), failed.detail)
        assertTrue(failed.detail.contains("the daemon refuses to load it"), failed.detail)
    }

    @Test
    fun `a project table setting both system_prompt and system_prompt_file fails the doctor`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve(".splice"))
        Files.writeString(tmp.resolve(".splice/prompt.md"), "stay terse")
        Files.writeString(tmp.resolve("p.md"), "stay terse")
        val both = toml(tmp, projectMode = null).replace(
            "system_prompt = \"be careful here\"",
            "system_prompt = \"be careful here\"\nsystem_prompt_file = \"p.md\"",
        )

        val failed = projectRows(both).single { it.status == CheckStatus.FAIL }

        assertEquals("project-prompt:project:$tmp", failed.name)
        assertTrue(failed.detail.contains("both system_prompt and system_prompt_file"), failed.detail)
    }

    @Test
    fun `a replace layer discloses at INFO that earlier layers are substituted`(@TempDir tmp: Path) {
        val row = projectRows(toml(tmp, projectMode = "\"replace\"")).first()

        assertEquals(CheckStatus.INFO, row.status)
        assertTrue(row.detail.contains("every earlier layer"), row.detail)
        assertTrue(row.detail.contains("bare model with tools attached"), row.detail)
        assertTrue(requireNotNull(row.fix).contains("system_prompt_mode = \"append\""), row.fix.orEmpty())
    }

    /** V4-170: the strip mode decodes from TOML through the daemon's own loader. V4-171: it WARNs
     *  like replace — the client's instructions are edited, and what a pattern removes is the
     *  operator's to own. */
    @Test
    fun `a strip layer parses from TOML and discloses at INFO that the field is edited`(@TempDir tmp: Path) {
        val topology = TopologyLoader.parse(toml(tmp, projectMode = "\"strip\""))
        val row = projectRows(toml(tmp, projectMode = "\"strip\"")).first()

        assertEquals(SystemPromptMode.STRIP, topology.projects.values.single().systemPromptMode)
        assertEquals(CheckStatus.INFO, row.status)
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

    @Test
    fun `invalid tier declarations give doctor a safe named configuration failure`(@TempDir tmp: Path) {
        val invalid = listOf(
            """model_slots = { unknown = "m1" }""",
            """models = [{ id = "m1", slot = "opus" }]
               model_slots = { sonnet = "m1" }""",
        )
        invalid.forEach { declaration ->
            val source = toml(tmp, null).replace(
                """pinned_model = "m1"""",
                """pinned_model = "m1"
                ${declaration.trimIndent()}""",
            )
            val failure = assertThrows<IllegalArgumentException> { TopologyLoader.parse(source) }
            val row = DoctorTestPorts.configChecks().configurationChecks(
                DoctorTopology.Broken(SafeFailureText.render(failure)),
                tmp.resolve("splice.toml"),
            ).single()
            assertEquals(CheckStatus.FAIL, row.status)
            assertTrue(row.detail.contains("model_slots"), row.detail)
        }
    }

    @Test
    fun `doctor names a declared tier that the running roster cannot resolve`(@TempDir tmp: Path) {
        val rows = DoctorTestPorts.configChecks().configurationChecks(
            DoctorTopology.Parsed(TopologyLoader.parse(toml(tmp, null))),
            tmp.resolve("splice.toml"),
            unmappedTiers = mapOf("one" to mapOf("opus" to "fixture/absent")),
        )
        val row = rows.single { it.name == "model-slot:one:opus" }
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("fixture/absent"), row.detail)
        assertTrue(row.detail.contains("unmapped"), row.detail)
        assertTrue(row.fix.orEmpty().contains("models"), row.fix)
    }

    @Test
    fun `doctor reads unresolved slots from the served models payload without inventing null tiers`() {
        val payload = """{"heads":[{"head":"fixture","models":[
            {"id":"fixture/served","slot":"sonnet","resolved":true},
            {"id":"fixture/absent","slot":"opus","resolved":false},
            {"id":"fixture/unslotted","slot":null,"resolved":false}
        ]}]}"""
        assertEquals(
            mapOf("fixture" to mapOf("opus" to "fixture/absent")),
            DoctorTierChecks.parse(payload),
        )
    }

    private fun projectRows(toml: String) = DoctorTestPorts.configChecks()
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
