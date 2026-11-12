// Reading JSON text the front did not write (a request body, a holder's line, the daemon's answer, a kept file):
// one object, or null when the text is not one, so a bad message is dropped or refused in words, never a crash.
package splice.hub.front

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object JsonText {
    fun objectOrNull(text: String): JsonObject? =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (ignored: SerializationException) {
            null
        }
}
