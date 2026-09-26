// NEW: V4-110 — the promoted knobs and quirks, pinned on the shipped example rather than the
// source. tool_name_cap is the one NEW live quirk the example ships (muse's built-in 64), so a
// non-default value must reach the parsed field — the reasoning_cache round-trip law, applied to
// the new key rather than left decorative. Lives here, not in ExampleConfigTest, which is at
// detekt's LargeClass ceiling.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.config.Knob
import splice.core.topology.Dialect
import splice.topology.TopologyLoader

/** A commented `# key = value` line of the example's KNOB REFERENCE block. */
private val KNOB_LINE = Regex("""# [A-Za-z][A-Za-z0-9]* = .*""")

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

    // V4-317: the KNOB REFERENCE says each key is settable in [defaults] or [heads.<key>.overrides], both
    // string maps (Topology.kt:40, HeadConfig.kt:28), and wrote 31 of its values as bare numbers and
    // booleans, which ktoml refuses at boot (IllegalTypeException, as CI run 36253801306's probe boot
    // did). The whole block, uncommented into [defaults], must boot, and every key it names must be a knob.
    @Test
    fun `the knob reference uncommented into defaults boots and names only knobs - V4-317`() {
        val reference = knobReference()
        val defaults = TopologyLoader.parse("[defaults]\n" + reference.joinToString("\n")).defaults
        assertEquals(reference.size, defaults.size, "every reference line is one [defaults] entry: $defaults")
        val knobs = Knob.entries.map { it.key }.toSet()
        assertEquals(emptyList<String>(), defaults.keys.filter { it !in knobs }, "reference keys that are no knob")
    }

    /** The block's `# key = value` lines, uncommented; its prose and continuation lines are no keys. */
    private fun knobReference(): List<String> {
        val lines = exampleToml().lines()
        val start = lines.indexOfFirst { "KNOB REFERENCE" in it }
        val end = lines.indexOfFirst { it.startsWith("# ── ENVIRONMENT") }
        check(start in 0 until end) { "the KNOB REFERENCE block is not where the example keeps it" }
        return lines.subList(start, end).filter { KNOB_LINE.matches(it) }.map { it.removePrefix("# ") }
            .also { check(it.isNotEmpty()) { "no `# key = value` line in the KNOB REFERENCE block" } }
    }

    private fun exampleToml(): String =
        checkNotNull(javaClass.getResourceAsStream("/splice.example.toml")) {
            "splice.example.toml is not on the classpath"
        }.bufferedReader().use { it.readText() }
}
