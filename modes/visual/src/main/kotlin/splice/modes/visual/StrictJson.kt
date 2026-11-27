// NEW: a parsed value as the compact JSON text a card's JSON.parse reads (modes/visual). The daemon's parser reads a
// bare word, a leading zero, a plus sign or NaN as a literal and would write it back as it came, so a card handed such
// a feed would be handed text it cannot parse: the text is written here, and only when every literal in it is strict.
package splice.modes.visual

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.CharacterCodingException

private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

internal class StrictJson {
    /** The compact bytes, or null when a token or an unpaired surrogate cannot be written without changing [value]. */
    fun bytes(value: JsonElement): ByteArray? = try {
        text(value)?.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

    /** [value] as compact JSON, or null when a literal in it, at any depth, is not a quoted string, `true`, `false`,
     *  `null` or a number as JSON spells one. It is written from a list of its own and not by recursion, so a deeply
     *  nested value cannot overflow the stack. Each entry of [pending] is a value still to write or text to append. */
    private fun text(value: JsonElement): String? {
        val out = StringBuilder()
        val pending = ArrayDeque<Any>()
        pending.addLast(value)
        while (pending.isNotEmpty()) {
            when (val next = pending.removeLast()) {
                is String -> out.append(next)
                is JsonObject -> open(out, pending, next)
                is JsonArray -> open(out, pending, next)
                is JsonNull -> out.append("null")
                is JsonPrimitive -> if (next.isString || literal(next.content)) out.append(next) else return null
            }
        }
        return out.toString()
    }

    /** Writes `{` and leaves its members and `}` to come, in order: pushed last first, as [pending] is taken. */
    private fun open(out: StringBuilder, pending: ArrayDeque<Any>, members: JsonObject) {
        out.append('{')
        pending.addLast("}")
        members.entries.reversed().forEachIndexed { fromEnd, (key, value) ->
            pending.addLast(value)
            val comma = if (fromEnd == members.size - 1) "" else ","
            pending.addLast("$comma${JsonPrimitive(key)}:")
        }
    }

    private fun open(out: StringBuilder, pending: ArrayDeque<Any>, items: JsonArray) {
        out.append('[')
        pending.addLast("]")
        items.reversed().forEachIndexed { fromEnd, item ->
            pending.addLast(item)
            if (fromEnd != items.size - 1) pending.addLast(",")
        }
    }

    private fun literal(text: String): Boolean = text == "true" || text == "false" || JSON_NUMBER.matches(text)
}
