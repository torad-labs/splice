package splice.sessions.v4367

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.restartRequiredKnobKeys
import splice.core.util.EnvReader
import java.nio.file.Path

class MessageEdgesKnobTest {
    @Test
    fun `message edges defaults on but accepts a restart-required off switch`(@TempDir root: Path) {
        val config = ConfigService(StatePaths(baseOverride = root.resolve("state")))
        assertEquals(true, config.getConfig().asMap()["messageEdges"])
        assertTrue("messageEdges" in restartRequiredKnobKeys)

        val patch = config.patch(mapOf("messageEdges" to false))
        assertEquals(false, patch.applied["messageEdges"])

        val env = EnvReader { name -> if (name == "SPLICE_MESSAGE_EDGES") "false" else null }
        val boot = ConfigService(StatePaths(baseOverride = root.resolve("env-state")), envReader = env)
        assertEquals(false, boot.getConfig().asMap()["messageEdges"])
    }
}
