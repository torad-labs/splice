// NEW: read_me (modes/visual): the guidance for drawing, returned on call rather than placed in every request. The core
// (when a visual beats text, the rules every visual keeps) always comes back; the modules come back as asked for, every
// one when the call names none. The texts are this module's resources, splice/modes/visual/guide/<part>.md.
package splice.modes.visual

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.modes.api.ModeAnswer

private const val GUIDE = "splice/modes/visual/guide"
private const val CORE = "core"
private val MODULES = listOf("chart", "comparison", "diagram", "diff", "explainer", "tool", "page", "live")

internal class VisualGuide {
    fun answer(input: JsonObject): ModeAnswer {
        val asked = input["modules"]?.takeUnless { it is JsonNull }
        val named = when (asked) {
            null -> MODULES
            is JsonArray -> asked.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            is JsonObject, is JsonPrimitive ->
                return ModeAnswer.Error("read_me takes modules as a list of names: ${MODULES.joinToString()}")
        }
        val unknown = named.filter { it !in MODULES }
        if (unknown.isNotEmpty()) {
            return ModeAnswer.Error(
                "read_me has no module ${unknown.joinToString { it ?: "that is not a name" }}; " +
                    "its modules are ${MODULES.joinToString()}",
            )
        }
        val parts = listOf(CORE) + MODULES.filter { it in named }
        return ModeAnswer.Text(parts.joinToString("\n\n") { part(it).trim() })
    }

    private fun part(name: String): String =
        checkNotNull(ClassLoader.getSystemResourceAsStream("$GUIDE/$name.md")?.use { it.readAllBytes() }) {
            "the visual mode carries no guide $name.md"
        }.decodeToString()
}
