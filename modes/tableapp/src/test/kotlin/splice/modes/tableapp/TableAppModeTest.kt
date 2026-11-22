package splice.modes.tableapp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeCatalogReader
import splice.modes.api.ModeSession
import splice.modes.api.ModeStore

class TableAppModeTest {
    private class NoStore : ModeStore {
        override fun read(session: String): String? = null
        override fun write(session: String, text: String) = Unit
    }

    private val mode = TableAppMode()
    private val session = ModeSession("s-1", null, "codex", "", ModeCaller.DOOR)

    private fun call(tool: String, vararg input: Pair<String, JsonPrimitive>): ModeAnswer =
        mode.answer(ModeCall(tool, JsonObject(mapOf(*input)), session, NoStore()))

    @Test
    fun `manifest loads cleanly through ModeCatalog and is off by default`() {
        val loaded = ModeCatalogReader(TableAppModeTest::class.java.classLoader).read()
        assertEquals(emptyList<String>(), loaded.findings, loaded.findings.joinToString("\n"))
        val found = loaded.modes.single { it.name == "tableapp" }
        assertEquals(false, found.manifest.on)
        assertEquals(listOf("render_table"), found.tools.map { it.name })
        assertEquals(listOf("ui://tableapp/table"), found.resources.map { it.uri })
    }

    @Test
    fun `render_table formats a valid Markdown table and rejects invalid input`() {
        val md = "| Model | Speed |\n| --- | --- |\n| Alpha | 120 tok/s |\n| Beta | 85 tok/s |"
        val ok = call("render_table", "title" to JsonPrimitive("Benchmarks"), "markdown" to JsonPrimitive(md))
        assertTrue(ok is ModeAnswer.Text && "Benchmarks" in ok.text && "Alpha | 120 tok/s" in ok.text, "$ok")

        val bad = call("render_table", "markdown" to JsonPrimitive("not a table"))
        assertTrue(bad is ModeAnswer.Error, "$bad")
        assertNull(mode.resource("ui://tableapp/table"))
    }
}
