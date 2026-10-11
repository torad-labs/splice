// PORT-OF: CodeModeSchemaTypes.kt @ 155cc76a3 — invariants: unchanged scalar, pointer and literal-bound rendering.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.util.HexFormat

/** The renderer's pure helpers: JSON scalars, JSON pointers, literal bounds, property names. */
internal object SchemaText {
    fun bytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    /** A JSON string's text; a number, boolean or null is not one. */
    fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.let { JsonScalars.str(it) }

    /** A JSON boolean; the string "true" is not one. */
    fun flag(element: JsonElement?): Boolean? =
        (element as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.let { JsonScalars.str(it) }
            ?.toBooleanStrictOrNull()

    fun hasRenderableKeywords(map: JsonObject): Boolean = RENDERABLE_KEYWORDS.any(map::containsKey)

    fun hasPropertyDescription(value: JsonElement): Boolean =
        string((value as? JsonObject)?.get("description"))?.isNotEmpty() == true

    fun propertyName(name: String): String =
        if (CodeModeManual.identifier(name) == name) name else JsonPrimitive(name).let(JsonWire::string)

    /** `#` plus a percent-decoded JSON pointer, or null for anything that is not a local fragment. */
    fun localJsonPointer(reference: String): String? {
        if (!reference.startsWith("#")) return null
        val pointer = percentDecode(reference.substring(1)) ?: return null
        return pointer.takeIf { it.isEmpty() || it.startsWith("/") }
    }

    /** RFC 6901, as serde_json's Value::pointer reads it (array indexes without `+` or leading zeros). */
    fun resolve(root: JsonElement, pointer: String): JsonElement? {
        if (pointer.isEmpty()) return root
        return pointer.substring(1).split("/").fold<String, JsonElement?>(root) { node, raw ->
            val token = raw.replace("~1", "/").replace("~0", "~")
            when (node) {
                is JsonObject -> node[token]
                is JsonArray -> arrayIndex(token)?.let(node::getOrNull)
                null, is JsonPrimitive -> null
            }
        }
    }

    fun literalUpperBound(value: JsonElement): Int = when (value) {
        is JsonObject -> value.entries.fold(2) { size, (key, item) ->
            size + OBJECT_ENTRY_BYTES + bytes(key) * ESCAPE_WIDTH + literalUpperBound(item)
        }
        is JsonArray -> value.fold(2) { size, item -> size + 1 + literalUpperBound(item) }
        is JsonPrimitive -> if (value.isString) bytes(value.content) * ESCAPE_WIDTH + 2 else value.content.length
    }

    private fun arrayIndex(token: String): Int? =
        token.takeUnless { it.startsWith("+") || (it.startsWith("0") && it.length != 1) }?.toIntOrNull()

    private fun percentDecode(fragment: String): String? {
        val source = fragment.toByteArray(Charsets.UTF_8)
        // One char per byte, so a byte index reads the same hex digits here (codex decodes the bytes).
        val byteChars = String(source, Charsets.ISO_8859_1)
        val decoded = java.io.ByteArrayOutputStream(source.size)
        var index = 0
        while (index < source.size) {
            if (source[index] == '%'.code.toByte()) {
                if (!isHexPair(source, index + 1)) return null
                decoded.write(HexFormat.fromHexDigits(byteChars, index + 1, index + PERCENT_TRIPLET))
                index += PERCENT_TRIPLET
            } else {
                decoded.write(source[index].toInt())
                index += 1
            }
        }
        val bytes = decoded.toByteArray()
        val text = String(bytes, Charsets.UTF_8)
        return text.takeIf { it.toByteArray(Charsets.UTF_8).contentEquals(bytes) }
    }

    /** Two ASCII hex digits at [at]; any other byte (or the end) makes the fragment undecodable. */
    private fun isHexPair(source: ByteArray, at: Int): Boolean =
        (at until at + 2).all { position ->
            source.getOrNull(position)?.let { byte -> byte >= 0 && HexFormat.isHexDigit(byte.toInt()) } == true
        }
}

// why: codex's literal bound charges 4 bytes per object entry (quotes, colon, comma) beside its key and value.
private const val OBJECT_ENTRY_BYTES = 4

// why: JSON escaping widens one UTF-8 byte to at most a six-byte \uXXXX escape.
private const val ESCAPE_WIDTH = 6

// why: a percent escape is `%` plus two hex digits.
private const val PERCENT_TRIPLET = 3
private val RENDERABLE_KEYWORDS = listOf(
    "const", "enum", "anyOf", "oneOf", "allOf", "type", "properties", "additionalProperties", "required", "items",
    "prefixItems",
)
