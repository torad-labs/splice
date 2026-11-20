// NEW: a mode's JSON files (a tool's schema and _meta) read as JSON, or null when they are not JSON.
package splice.modes.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal object ModeJson {
    fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }
}
