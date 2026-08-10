// NEW: JsonNull-safe scalar reads (#924 Phase 3). JsonNull IS a JsonPrimitive whose content is the
// literal string "null", so the common `(x as? JsonPrimitive)?.content` / `x?.jsonPrimitive?.content`
// leaks a JSON `null` through as the STRING "null" — a null auth token becomes the literal token
// "null". Two of the copies (kimi) filtered it; ~a dozen inline reads did not. This is the one
// filtered reader. (`?.toLongOrNull()`/`?.toIntOrNull()` chains were already null-safe either way;
// the fix matters for the String reads.)
package splice.core.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The scalar content of this element, or null if it is absent, JSON `null`, or not a primitive. */
public fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/** Prefix-form non-null read: "" for absent / JSON null / non-primitive — the dialects' shared shape. */
public fun strOrEmpty(el: JsonElement?): String = el.str().orEmpty()

/**
 * The scalar ONLY when it is a JSON string: "" for a boolean, a number, JSON `null`, an absent key
 * or a non-primitive. [strOrEmpty] is the right read for a field the wire types as a string; this
 * is the read for a field whose PRESENCE-AS-A-STRING is itself the signal, so that a vendor which
 * ships it as a flag (`"refusal": false`, `"refusal": 0`) cannot be mistaken for a value — the
 * same JsonPrimitive-content leak this file exists for, one type axis wider.
 */
public fun strIfString(el: JsonElement?): String =
    (el as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/** The scalar string at [key] (JsonNull-safe). */
public fun JsonObject.str(key: String): String? = this[key].str()

/** The scalar at [key] parsed as Long, or null. */
public fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

/** The scalar at [key] parsed as Int, or null. */
public fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()
