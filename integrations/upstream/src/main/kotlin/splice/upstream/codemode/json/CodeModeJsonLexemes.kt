// PORT-OF: serde_json 1.0.149 parse_any_number/scan_exponent with release arbitrary_precision.
package splice.upstream.codemode.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Release Value numbers preserve their decimal text after serde's integer and exponent normalization. */
internal object CodeModeJsonLexemes {
    fun value(text: String): JsonPrimitive {
        val integer = if (text.startsWith('-')) text.toLongOrNull()?.toString() else text.toULongOrNull()?.toString()
        if (integer != null) return JSON_LEXEMES.parseToJsonElement(integer) as JsonPrimitive
        val exponent = text.indexOfFirst { it == 'e' || it == 'E' }
        val canonical = if (exponent < 0) {
            text
        } else {
            val suffix = text.substring(exponent + 1)
            text.substring(
                0,
                exponent,
            ) + "e" + (if (suffix.startsWith('+') || suffix.startsWith('-')) "" else "+") + suffix
        }
        return JSON_LEXEMES.parseToJsonElement(canonical) as JsonPrimitive
    }

    fun arbitrary(value: JsonPrimitive): Boolean =
        value.content.toLongOrNull() == null && value.content.toULongOrNull() == null
}

private val JSON_LEXEMES = Json
