// NEW: V4-110 — the promoted knobs and quirks, pinned on the shipped example rather than the
// source. tool_name_cap is the one NEW live quirk the example ships (muse's built-in 64), so a
// non-default value must reach the parsed field — the reasoning_cache round-trip law, applied to
// the new key rather than left decorative. Lives here, not in ExampleConfigTest, which is at
// detekt's LargeClass ceiling.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.TopologyLoader
import splice.core.topology.Dialect
import java.nio.file.Files
import java.nio.file.Paths

class PromotedKnobsExampleTest {

    @Test
    fun `the promoted tool_name_cap quirk reaches the parsed muse profile`() {
        val topology = TopologyLoader.parse(exampleToml())
        val muse = topology.providers[topology.heads["claude-muse"]!!.provider]!!
        assertEquals(Dialect.ANTHROPIC_PASSTHROUGH, muse.dialect)
        assertEquals(64, muse.quirks.toolNameCap, "muse tool_name_cap must ride as a parsed value, not a comment")
    }

    @Test
    fun `the promoted code-mode pool knobs stay absent so their code defaults ride`() {
        val topology = TopologyLoader.parse(exampleToml())
        val codex = topology.providers[topology.heads["claudex"]!!.provider]!!
        assertNull(codex.quirks.codeModeWorkers, "code_mode_workers is commented, so its default rides")
        assertNull(codex.quirks.codeModeTimeoutMs)
        assertNull(codex.quirks.codeModeHeapMb)
    }

    private fun exampleToml(): String {
        var dir = Paths.get("").toAbsolutePath()
        repeat(4) {
            val candidate = dir.resolve("config").resolve("splice.example.toml")
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent ?: return@repeat
        }
        error("config/splice.example.toml not found from ${Paths.get("").toAbsolutePath()}")
    }
}
