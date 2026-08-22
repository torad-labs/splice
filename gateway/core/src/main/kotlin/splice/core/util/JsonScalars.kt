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
}
