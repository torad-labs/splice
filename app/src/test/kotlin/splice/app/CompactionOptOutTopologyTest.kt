package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.compaction.CompactionInstructions
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class CompactionOptOutTopologyTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `topology parser retains explicit project-model opt-out`() {
        val project = tmp.resolve("repo").toAbsolutePath()
        val topology = TopologyLoader.parse(
            """
            [compaction]
            instructions = "global"

            [[compaction.project]]
            path = "$project"
            model = "wire-model"
            instructions = ""
            """.trimIndent(),
        )

        val rule = topology.compaction.project.single()
        assertEquals(project.toString(), rule.path)
        assertEquals("wire-model", rule.model)
        assertEquals("", rule.instructions)
    }

    /** The operator's path: splice.toml text in, the instructions a compaction gets out. */
    @Test
    fun `a splice toml's compaction tables resolve by precedence, with file text read from beside the toml`() {
        val project = tmp.resolve("repo").toAbsolutePath()
        Files.writeString(tmp.resolve("global.md"), "from the global file")
        val topology = TopologyLoader.parse(
            """
            [compaction]
            file = "global.md"

            [[compaction.model]]
            model = "astra"
            instructions = "for astra"

            [[compaction.project]]
            path = "$project"
            instructions = "for the repo"

            [[compaction.project]]
            path = "$project"
            model = "astra"
            instructions = ""
            """.trimIndent(),
        )
        val instructions = CompactionInstructions(topology.compaction, tmp)

        fun textFor(model: String, cwd: Path?) = instructions.resolve(model, cwd).text

        assertEquals("from the global file", textFor("other", tmp.resolve("elsewhere").toAbsolutePath()))
        assertEquals("for astra", textFor("astra", tmp.resolve("elsewhere").toAbsolutePath()))
        assertEquals("for the repo", textFor("other", project.resolve("src")))
        assertEquals("", textFor("astra", project.resolve("src")), "the project-model entry opts out")
        assertEquals("for astra", textFor("astra", null), "an unknown session falls to its model rule")
        assertEquals("from the global file", textFor("other", null))
    }

    @Test
    fun `a rule that sets both instructions and file refuses to boot`() {
        val topology = TopologyLoader.parse(
            """
            [compaction]
            instructions = "inline"
            file = "also.md"
            """.trimIndent(),
        )

        assertThrows<IllegalArgumentException> { CompactionInstructions(topology.compaction, tmp) }
    }
}
