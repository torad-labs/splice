// NEW: JsonNull-safe scalar reads (#924 Phase 3). JsonNull IS a JsonPrimitive whose content is the
// literal string "null", so the common `(x as? JsonPrimitive)?.content` / `x?.jsonPrimitive?.content`
// leaks a JSON `null` through as the STRING "null" — a null auth token becomes the literal token
// "null". Two of the copies (kimi) filtered it; ~a dozen inline reads did not. This is the one
// filtered reader. (`?.toLongOrNull()`/`?.toIntOrNull()` chains were already null-safe either way;
// the fix matters for the String reads.)
// The reads were top-level EXTENSIONS on kotlinx's JsonElement/JsonObject until the 2026-08-16 style
// migration (HD-M8). Those receivers are foreign types, so the members cannot live on them; the
// sanctioned home is this named object (the migration's pattern 5) and the receiver became the first
// parameter — `el.str()` reads `JsonScalars.str(el)`. Same bodies, same names, same semantics.
package splice.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public object JsonScalars {

    /** The scalar content of [el], or null if it is absent, JSON `null`, or not a primitive. */
    public fun str(el: JsonElement?): String? = (el as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    /** Prefix-form non-null read: "" for absent / JSON null / non-primitive — the dialects' shared shape. */
    public fun strOrEmpty(el: JsonElement?): String = str(el).orEmpty()

    /**
     * The scalar ONLY when it is a JSON string: "" for a boolean, a number, JSON `null`, an absent key
     * or a non-primitive. [strOrEmpty] is the right read for a field the wire types as a string; this
     * is the read for a field whose PRESENCE-AS-A-STRING is itself the signal, so that a vendor which
     * ships it as a flag (`"refusal": false`, `"refusal": 0`) cannot be mistaken for a value — the
     * same JsonPrimitive-content leak this file exists for, one type axis wider.
     */
    public fun strIfString(el: JsonElement?): String =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /**
     * The scalar string at [key] of [obj] (JsonNull-safe).
     *
     * [obj] is NULLABLE for one reason: as an extension these reads were reached through safe calls
     * (`tokens?.str(FIELD_ACCESS_TOKEN)`), and a null receiver yielded null. Taking the receiver as a
     * nullable parameter keeps those call sites a plain receiver-to-argument move — a null [obj]
     * still reads null — instead of wrapping each one in a `?.let { }`. Nothing that compiled before
     * changes meaning; only calls that could not previously be written are newly expressible.
     */
    public fun str(obj: JsonObject?, key: String): String? = str(obj?.get(key))

    /** The scalar at [key] of [obj] parsed as Long, or null (null [obj] reads null — see [str]). */
    public fun long(obj: JsonObject?, key: String): Long? = str(obj, key)?.toLongOrNull()

    /** The scalar at [key] of [obj] parsed as Int, or null (null [obj] reads null — see [str]). */
    public fun int(obj: JsonObject?, key: String): Int? = str(obj, key)?.toIntOrNull()

    /**
     * The first of [keys] in [obj] that reads as a number, or null — THE usage-alias chain (CX-18).
     *
     * Order is precedence: the canonical spelling comes first, so a backend emitting both a standard
     * field and a vendor alias is read by the standard one.
     *
     * Parsing is deliberately `toDoubleOrNull()?.toLong()`, not `toLongOrNull()`: JS-relay backends
     * emit token counts as floats (`"prompt_tokens": 1500.0`) and a strict Long parse reads those as
     * ABSENT, silently falling through to a zero bucket. This is the semantics the gateway's own
     * usage reader already had; unifying on the strict one would have been a downgrade.
     *
     * Every alias read in the tree routes here — the dialect translators, the Responses harvest and
     * the HUD payload builder — so a newly discovered alias is added in exactly one place. The
     * campaign item's instruction was explicit about this: lift the chain into :core "rather than
     * adding a fourth hand-rolled reader".
     */
    public fun firstLong(obj: JsonObject?, vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            str(obj?.get(key))?.toDoubleOrNull()?.toLong()
        }

    /** [text] parsed by [json] as an object; null when it is not valid JSON or not an object. */
    public fun objectOrNull(json: Json, text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    /** [text] parsed by [json] as an object only when it is JSON as RFC 8259 writes it ([JsonText]): kotlinx's tree
     *  parser alone takes an unquoted token, a raw control character inside a string and a key written twice. With
     *  [lastKeyWins], a repeated key keeps its last value, as `JSON.parse` reads a file another program owns. */
    public fun strictObjectOrNull(json: Json, text: String, lastKeyWins: Boolean = false): JsonObject? =
        objectOrNull(json, text)?.takeIf { JsonText(text, lastKeyWins).valid() }

    /** [text] read once from its start; [valid] answers whether it is JSON exactly as RFC 8259 writes it. kotlinx's
     *  tree parser takes an unquoted token, a raw control character inside a string and a key written twice (keeping
     *  the last) without a word; this reads the raw text before it. A file another program reads (Claude Code's
     *  managed settings, read by `JSON.parse`) keeps that program's one leniency, the last of a repeated key. */
    private class JsonText(private val text: String, private val lastKeyWins: Boolean) {
        private var at = 0

        /** Whether [text] is one JSON value, with white space around it alone, whose strings carry no raw control
         *  character and whose objects name no key twice unless [lastKeyWins]. */
        fun valid(): Boolean = value(0) && space() == text.length

        private fun value(depth: Int): Boolean {
            space()
            val char = text.getOrNull(at)
            val spelled = char?.let(words::get)
            return when {
                char == '{' || char == '[' -> depth < JSON_NESTING_LIMIT && nested(char, depth + 1)
                char == '"' -> string() != null
                spelled != null -> word(spelled)
                else -> number()
            }
        }

        /** The object or array [open] at the cursor, through its closing bracket. */
        private fun nested(open: Char, depth: Int): Boolean {
            at++
            space()
            val close = if (open == '{') '}' else ']'
            if (take(close)) return true
            val keys = if (open == '{') HashSet<String>() else null
            var read: Boolean
            do {
                read = (keys == null || member(keys)) && value(depth)
                space()
            } while (read && take(','))
            return read && take(close)
        }

        /** The key and colon of one object member: false when the key is no string, or one [keys] already holds
         *  while a repeated key is refused. */
        private fun member(keys: MutableSet<String>): Boolean {
            space()
            val key = string()
            space()
            val fresh = key != null && (keys.add(key) || lastKeyWins)
            return fresh && take(':')
        }

        /** The string at the cursor as it decodes, or null when it is none: unterminated, holding a raw control
         *  character, or an escape JSON does not have. */
        private fun string(): String? {
            if (!take('"')) return null
            val decoded = StringBuilder()
            while (!take('"')) decoded.append(character() ?: return null)
            return decoded.toString()
        }

        /** The next character of a string as it decodes, or null at the end of the text, at a raw control
         *  character or at a bad escape. */
        private fun character(): Char? {
            val char = text.getOrNull(at++)
            return when {
                char == null || char < ' ' -> null
                char == '\\' -> escaped()
                else -> char
            }
        }

        private fun escaped(): Char? {
            val char = text.getOrNull(at++)
            return if (char == 'u') unicode() else char?.let { escapedAs.getOrNull(escapeLetters.indexOf(it)) }
        }

        /** The four hex digits after `\u` as the character they name, or null when they are not four hex digits. */
        private fun unicode(): Char? {
            val digits = unicodeDigits.matchAt(text, at)?.value ?: return null
            at += digits.length
            return digits.hexToInt().toChar()
        }

        private fun number(): Boolean {
            val read = jsonNumber.matchAt(text, at) ?: return false
            at = read.range.last + 1
            return true
        }

        private fun word(spelled: String): Boolean =
            text.startsWith(spelled, at).also { if (it) at += spelled.length }

        private fun take(char: Char): Boolean = (text.getOrNull(at) == char).also { if (it) at++ }

        /** Moves the cursor past JSON's white space and answers where it now stands. */
        private fun space(): Int {
            while (at < text.length && text[at] in JSON_SPACE) at++
            return at
        }

        private val words = mapOf('t' to "true", 'f' to "false", 'n' to "null")

        /** The letters JSON allows after a backslash, each at the index of the character it stands for in
         *  [escapedAs]. */
        private val escapeLetters = "\"\\/bfnrt"
        private val escapedAs = "\"\\/\b\u000C\n\r\t"

        private val unicodeDigits = Regex("[0-9a-fA-F]{4}")

        private val jsonNumber = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
    }
}

// why: no settings file or check-in nests this deep; the cap keeps a crafted body from exhausting the stack.
private const val JSON_NESTING_LIMIT = 512

// JSON's four white-space characters, the only ones allowed between its tokens.
private const val JSON_SPACE = " \t\n\r"
