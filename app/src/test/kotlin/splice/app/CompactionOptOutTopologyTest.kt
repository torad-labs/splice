package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.topology.TopologyLoader
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
}
