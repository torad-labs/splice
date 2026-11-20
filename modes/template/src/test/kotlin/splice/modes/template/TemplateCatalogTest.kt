package splice.modes.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.modes.api.ModeCatalogReader

/** The template read the way splice's catalog reads it at boot, from this module's own classpath: whole, or failing
 *  with every finding at once. */
class TemplateCatalogTest {
    private val catalog = ModeCatalogReader(Thread.currentThread().contextClassLoader).read()

    @Test
    fun `the mode loads through the catalog with no finding`() {
        assertTrue(catalog.findings.isEmpty()) {
            "mode template is not whole:\n" + catalog.findings.joinToString("\n")
        }
        val mode = checkNotNull(catalog.mode("template")) { "the catalog found no mode template" }
        assertEquals(mode.manifest.tools, mode.tools.map { it.name })
        mode.tools.forEach { tool ->
            assertEquals("mcp__${mode.manifest.server}__${tool.name}", tool.offeredAs)
            assertTrue(tool.description.isNotEmpty()) { "${tool.name} has no description" }
        }
    }
}
