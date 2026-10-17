// NEW: V4-156 — CodeModeFrames' strict field readers, moved verbatim out of CodeModeWire.kt (only
// their visibility changed, private to internal-by-object, so CodeModeFrames can still call them).
// DECOMPOSED FOR THE CONCENTRATION RATIO, NOT FOR A DEFECT: CodeModeWire.kt barely changed (own 15
// percent of its movement, measured with --since 608f63b9) and was lifted into band HIGH by its
// neighbours getting smaller. Nothing here was wrong where it was.
package splice.codemode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException

/** Each reader throws IOException naming the field, so a malformed worker frame fails the cell
 *  rather than being read as absence. */
internal object CodeModeFields {
    fun requireKeys(frame: JsonObject, expected: Set<String>) {
        if (frame.keys != expected) throw IOException("Code-mode protocol fields are invalid")
    }

    fun ensureWorker(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }

    fun requiredObject(element: JsonElement?, message: String): JsonObject =
        element as? JsonObject ?: throw IOException(message)

    fun requiredString(frame: JsonObject, name: String): String =
        (frame[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: throw IOException("Code-mode protocol field $name must be a string")

    fun requiredBoolean(frame: JsonObject, name: String): Boolean =
        (frame[name] as? JsonPrimitive)?.booleanOrNull
            ?: throw IOException("Code-mode protocol field $name must be a boolean")

    fun requiredArray(frame: JsonObject, name: String): JsonArray =
        frame[name] as? JsonArray ?: throw IOException("Code-mode protocol field $name must be an array")
}
