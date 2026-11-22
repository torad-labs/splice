// The template app's handler: it answers show_note with the note itself. A client that draws MCP Apps shows the page
// the tool names (resources/note.html) with the call's arguments; any other client shows this answer as the result.
package splice.modes.templateapp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.modes.api.Mode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall

private const val SHOW_NOTE = "show_note"

/** Answers show_note with the note's title, when it has one, and its text. */
internal class TemplateAppMode : Mode {
    override fun answer(call: ModeCall): ModeAnswer {
        if (call.tool != SHOW_NOTE) return ModeAnswer.Error("there is no tool ${call.tool} in this mode")
        val text = string(call.input, "text") ?: return ModeAnswer.Error("show_note needs a text, as a string")
        return ModeAnswer.Text(listOfNotNull(string(call.input, "title"), text).joinToString("\n\n"))
    }

    private fun string(input: JsonObject, key: String): String? =
        (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
