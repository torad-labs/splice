// NEW: a drawing call's input checked (modes/visual): its title, its code under the size cap, and a page's height.
package splice.modes.visual

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// why: a host that draws MCP Apps carries a visual's code to its view, and one known host draws nothing past 256 KiB;
// more than that is a file to send, not a reply. The same cap bounds every page the mode keeps on disk.
private const val MAX_CODE_KIB = 256

// why: the cap above in bytes, as the code is measured (UTF-8).
private const val MAX_CODE_BYTES = MAX_CODE_KIB * 1024

// why: a title is read above the visual in one line; hosts cut it at 200 characters.
private const val TITLE_MOST_CHARS = 200

// why: the tallest frame a page may ask for, in CSS pixels; the view reports its real height to the host anyway.
private const val MAX_HEIGHT = 10_000

/** A drawing call's input, checked: what to draw, or why the call is refused, in words the model reads. */
internal sealed class Drawing {
    data class Ready(val title: String, val code: String, val height: Int?) : Drawing()

    data class Refused(val why: String) : Drawing()
}

/** Reads the input of [tool], whose code is under [codeKey]. */
internal class DrawingRead(private val tool: String, private val codeKey: String) {
    fun read(input: JsonObject): Drawing {
        val title = text(input, "title")?.trim()?.takeIf(String::isNotEmpty)?.take(TITLE_MOST_CHARS)
        val code = text(input, codeKey)?.takeIf(String::isNotBlank)
        val height = input["height"]?.takeUnless { it is JsonNull }
        val pixels = height?.let(::pixels)
        return when {
            title == null -> Drawing.Refused("$tool needs a title, as a string")
            code == null -> Drawing.Refused("$tool needs $codeKey, as a string")
            code.encodeToByteArray().size > MAX_CODE_BYTES -> Drawing.Refused(tooLarge(code))
            height != null && pixels == null ->
                Drawing.Refused("height is a whole number of CSS pixels from 1 to $MAX_HEIGHT")
            else -> Drawing.Ready(title, code, pixels)
        }
    }

    private fun tooLarge(code: String): String =
        "$codeKey is ${code.encodeToByteArray().size} bytes, and a visual holds at most $MAX_CODE_KIB KiB " +
            "($MAX_CODE_BYTES bytes): draw less, or split it into two visuals"

    /** A height in CSS pixels, from 1 to [MAX_HEIGHT], or null for anything else. */
    private fun pixels(height: JsonElement): Int? =
        (height as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..MAX_HEIGHT }

    private fun text(input: JsonObject, key: String): String? =
        (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
