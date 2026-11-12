package splice.core.config.v4387

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import java.nio.file.Path

class DefaultTraceKnobTest {
    @Test
    fun `an explicit per-head false disables only that head`(@TempDir root: Path) {
        val config = ConfigService(
            StatePaths(baseOverride = root.resolve("state")),
            perHeadOverrides = mapOf("claude-muse" to mapOf("trace" to "false")),
        )
        assertFalse(config.getConfig("claude-muse").trace)
        assertTrue(config.getConfig("claudex").trace)
    }
}
