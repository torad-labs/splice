package splice.modes.templateapp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeSession
import splice.modes.api.ModeStore

/** The template app's handler, through the contract, as splice calls it. */
class TemplateAppModeTest {
    private class NoStore : ModeStore {
        override fun read(session: String): String? = null

        override fun write(session: String, text: String) = Unit
    }

    private val mode = TemplateAppMode()
    private val session = ModeSession("s-1", null, "astra", "", ModeCaller.HEAD)

    private fun call(tool: String, vararg input: Pair<String, JsonPrimitive>): ModeAnswer =
        mode.answer(ModeCall(tool, JsonObject(mapOf(*input)), session, NoStore()))

    @Test
    fun `show_note answers with the note, its title first when it has one`() {
        assertEquals(ModeAnswer.Text("High at 04:10."), call("show_note", "text" to JsonPrimitive("High at 04:10.")))
        assertEquals(
            ModeAnswer.Text("Tide\n\nHigh at 04:10."),
            call("show_note", "title" to JsonPrimitive("Tide"), "text" to JsonPrimitive("High at 04:10.")),
        )
    }

    @Test
    fun `a call without a string text, or of a tool it does not have, is an error answer that says so`() {
        val untexted = call("show_note", "text" to JsonPrimitive(7))
        assertTrue(untexted is ModeAnswer.Error && "text" in untexted.text) { "$untexted" }
        val unknown = call("burn_note", "text" to JsonPrimitive("x"))
        assertTrue(unknown is ModeAnswer.Error && "burn_note" in unknown.text) { "$unknown" }
    }

    @Test
    fun `it gives no content of its own for a resource, since its page is a file`() {
        assertEquals(null, mode.resource("ui://templateapp/note"))
    }
}
