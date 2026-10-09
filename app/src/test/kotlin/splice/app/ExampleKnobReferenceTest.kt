package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.config.Knob
import splice.topology.TopologyLoader

/** A commented `# key = value` line of the example's KNOB REFERENCE block. */
private val KNOB_LINE = Regex("""# [A-Za-z][A-Za-z0-9]* = .*""")

class ExampleKnobReferenceTest {

    // The KNOB REFERENCE says each key is settable in [defaults] or [heads.<key>.overrides], both string
    // maps, so a bare number or boolean is refused at boot. The whole block, uncommented into [defaults],
    // must boot, and every key it names must be a knob.
    @Test
    fun `the knob reference uncommented into defaults boots and names only knobs`() {
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
