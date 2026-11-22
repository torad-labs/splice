package splice.modes.templateapp

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.modes.api.ModeCatalogReader
import splice.modes.api.ModeKind

/** The template app read the way splice's catalog reads it at boot, from this module's own classpath: whole, an mcp
 *  mode whose tool names a page the mode carries. */
class TemplateAppCatalogTest {
    private val catalog = ModeCatalogReader(Thread.currentThread().contextClassLoader).read()

    @Test
    fun `the mode loads through the catalog with no finding, as an mcp mode`() {
        assertTrue(catalog.findings.isEmpty()) {
            "mode templateapp is not whole:\n" + catalog.findings.joinToString("\n")
        }
        val mode = checkNotNull(catalog.mode("templateapp")) { "the catalog found no mode templateapp" }
        assertEquals(ModeKind.MCP, mode.manifest.kind)
        assertEquals(listOf("show_note"), mode.tools.map { it.name })
        assertEquals("mcp__templateapp__show_note", mode.tools.single().offeredAs)
    }

    @Test
    fun `the page its tool names is a resource the mode carries, an MCP App with its content`() {
        val mode = checkNotNull(catalog.mode("templateapp"))
        val meta = checkNotNull(mode.tools.single().meta) { "show_note has no _meta" }
        val named = meta.getValue("ui").jsonObject.getValue("resourceUri").jsonPrimitive.content
        val page = mode.resources.single()
        assertEquals(named, page.uri)
        assertEquals("text/html;profile=mcp-app", page.mimeType)
        val html = checkNotNull(page.content) { "the page has no content" }
        assertTrue(html.trimStart().startsWith("<!doctype html>")) { html.take(40) }
        // the page says it is there, and reads the arguments the tool was called with
        assertTrue("ui/initialize" in html && "ui/notifications/tool-input" in html)
        // it loads nothing from anywhere, as its content security policy says
        assertTrue(
            listOf("<script src", "<link ", "http://", "fetch(").none {
                it in html.replace("https://modelcontextprotocol.io", "")
            },
        )
    }
}
