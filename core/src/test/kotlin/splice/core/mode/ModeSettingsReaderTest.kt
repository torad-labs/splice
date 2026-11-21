package splice.core.mode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.capability.CapabilityToml

/** splice.toml's [modes.<name>] tables, read apart and checked all at once. */
class ModeSettingsReaderTest {
    private val reader = ModeSettingsReader(setOf("astra", "borealis"), setOf("template"))

    private fun read(text: String) = reader.read(CapabilityToml.split(text, MODE_TABLES).tables)

    @Test
    fun `a well-formed table rides as on and heads, inline or as a sub-table`() {
        val inline = read("[modes.template]\non = true\nheads = { borealis = false }\n")
        assertEquals(mapOf("template" to ModeSetting(true, mapOf("borealis" to false))), inline.settings)
        val sub = read("[modes.template.heads]\nastra = true\n")
        assertEquals(mapOf("template" to ModeSetting(null, mapOf("astra" to true))), sub.settings)
        assertTrue(inline.findings.isEmpty() && sub.findings.isEmpty())
    }

    @Test
    fun `every fault is a finding by its key path, and none quotes a value`() {
        val text = "[modes.ghost]\non = true\n[modes.template]\non = \"certainly\"\n" +
            "colour = \"vermilion\"\nheads = { nohead = true }\n"
        val read = read(text)
        val said = read.findings.joinToString("\n") { it.text() }
        listOf("modes.ghost", "modes.template.colour", "modes.template.on", "modes.template.heads.nohead").forEach {
            assertTrue(it in said) { "$it is not named in:\n$said" }
        }
        assertFalse("certainly" in said || "vermilion" in said) { said }
        assertTrue(read.settings.isEmpty())
    }
}
