package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DaemonTimingBootTest {
    @TempDir
    lateinit var dir: Path

    private val timing = listOf("upstreamTimeoutMs", "firstByteTimeoutMs", "streamIdleMs", "stallReanchorMs")
    private val topology = """
        [providers.test]
        dialect = "openai-chat"
        base_url = "https://example.invalid/v1"
        auth = { kind = "api-key", env = "TEST_KEY" }
        [[providers.test.models]]
        id = "test-model"
        context_window = 128000
        [heads.test]
        provider = "test"
        port = 8801
        discovery_prefix = "test/"
        pinned_model = "test-model"
    """.trimIndent()

    @Test
    fun `boot reports every per-head timing setting with a fix`() {
        val overrides = timing.joinToString("\n") { "$it = \"60000\"" }
        val read = ConfigFindings.read("$topology\n[heads.test.overrides]\n$overrides\n", dir)
        assertTrue(read is ConfigRead.Refused)
        val findings = (read as ConfigRead.Refused).findings
        assertEquals(timing.map { "heads.test.overrides.$it" }.toSet(), findings.map { it.path }.toSet())
        assertTrue(findings.all { "daemon-wide" in it.message && "remove the line" in it.message })
    }

    @Test
    fun `boot reports timing settings saved for one head and the global settings remain valid`() {
        val paths = StatePaths(baseOverride = dir)
        val saved = paths.headsDir.resolve("test").resolve(paths.configFile.fileName)
        Files.createDirectories(saved.parent)
        Files.writeString(saved, timing.joinToString(",", "{", "}") { "\"$it\":60000" })
        val env = EnvReader { key -> if (key == "SPLICE_STATE_DIR") dir.toString() else null }
        val defaults = timing.joinToString("\n") { "$it = \"60000\"" }
        val read = ConfigFindings.read("[defaults]\n$defaults\n$topology", dir, retired = RetiredSettings(env))
        assertTrue(read is ConfigRead.Refused)
        val findings = (read as ConfigRead.Refused).findings
        assertEquals(timing.map { "heads.test.overrides.$it" }.toSet(), findings.map { it.path }.toSet())
        assertTrue(findings.all { it.origin is splice.core.topology.TopologyFinding.Origin.Saved })
        Files.delete(saved)
        assertTrue(ConfigFindings.read("[defaults]\n$defaults\n$topology", dir) is ConfigRead.Ready)
    }
}
