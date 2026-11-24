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
        discovery_prefix = "claude-test--"
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

    /** upgrade-compat: a saved head value splice no longer reads is splice's own file, so it is a warning and the daemon
     *  clears it at its start after a copy; it never refuses boot. */
    @Test
    fun `timing settings saved for one head are warned of and cleared after a copy, and boot goes on`() {
        val paths = StatePaths(baseOverride = dir)
        val saved = paths.headsDir.resolve("test").resolve(paths.configFile.fileName)
        Files.createDirectories(saved.parent)
        Files.writeString(saved, timing.joinToString(",", "{", "}") { "\"$it\":60000" })
        val env = EnvReader { key -> if (key == "SPLICE_STATE_DIR") dir.toString() else null }
        val defaults = timing.joinToString("\n") { "$it = \"60000\"" }
        val beside = BootSettings(retired = RetiredSettings(env))
        val read = ConfigFindings.read("[defaults]\n$defaults\n$topology", dir, beside = beside)
        assertTrue(read is ConfigRead.Ready, "a saved setting never refuses boot: $read")
        val warnings = (read as ConfigRead.Ready).warnings
        assertEquals(timing.map { "heads.test.overrides.$it" }.toSet(), warnings.map { it.path }.toSet())
        assertTrue(warnings.all { it.origin is splice.core.topology.TopologyFinding.Origin.Saved })
        assertTrue(warnings.none { "60000" in it.text() }, "no value is said")

        val backups = dir.resolve("backups")
        val said = SavedSettingsPrune(backups).prune(warnings)
        assertEquals(timing.size, said.count { "cleared" in it }, "one line per cleared key: $said")
        assertEquals("{}", Files.readString(saved).trim(), "every daemon-wide key is cleared")
        val copy = Files.list(backups).use { it.toList() }.single()
        assertTrue(timing.all { it in Files.readString(copy) }, "the copy holds what was cleared")
        val again = ConfigFindings.read("[defaults]\n$defaults\n$topology", dir, beside = beside)
        assertEquals(emptyList<Any>(), (again as ConfigRead.Ready).warnings)
    }
}
