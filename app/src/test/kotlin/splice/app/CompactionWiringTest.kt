package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.compaction.CompactionScope
import splice.core.config.StatePaths
import splice.head.compaction.CompactionTail
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class CompactionWiringTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `daemon loads file-backed topology instructions and shares the resolver with every head`() {
        val instructions = tmp.resolve("model-compact.txt")
        Files.writeString(instructions, "retain decisions and evidence")
        val topologyPath = tmp.resolve("splice.toml")
        val topology = TopologyLoader.parse(
            """
            [compaction]
            file = "model-compact.txt"

            [[compaction.model]]
            model = "wire-model"
            instructions = "model-specific"
            """.trimIndent(),
        )
        val daemon = Daemon(
            topology = topology,
            statePaths = StatePaths(baseOverride = tmp.resolve("state")),
            dashboardHtml = { "" },
            log = {},
            topologyPath = topologyPath,
        )

        val tailField = Daemon::class.java.getDeclaredField("compactionTail").apply { isAccessible = true }
        val tail = tailField.get(daemon) as CompactionTail
        val resolved = tail.resolve(compact = true, wireModel = "wire-model", sessionId = "unknown-session")

        assertEquals("retain decisions and evidence", resolved?.text)
        assertEquals(CompactionScope.GLOBAL, resolved?.scope)
        assertTrue(resolved?.source?.endsWith("file:$instructions") == true, resolved?.source)
        assertEquals("model-specific", topology.compaction.model.single().instructions)

        val factoryField = Daemon::class.java.getDeclaredField("headServerFactory").apply { isAccessible = true }
        val factory = factoryField.get(daemon)
        val factoryTail = factory.javaClass.getDeclaredField("compactionTail")
            .apply { isAccessible = true }
            .get(factory)
        assertSame(tail, factoryTail, "all factory-built heads must receive the boot-time resolver")
    }

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
