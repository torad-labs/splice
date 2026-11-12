package splice.models.list

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

/** Older model catalogs carry literal instructions beside the canonical messages envelope. */
internal object ModelMessagesInput {
    fun of(row: JsonObject): JsonObject? {
        val supplied = row["model_messages"] as? JsonObject
        val legacy = (row["base_instructions"] as? JsonPrimitive)?.takeIf { it.isString }?.let(JsonScalars::str)
        val original = supplied ?: if (legacy != null) JsonObject(emptyMap()) else return null
        val template = original["instructions_template"]
        val absent = template == null || template === JsonNull
        val messages = if (absent && legacy != null) {
            JsonObject(original + ("instructions_template" to JsonPrimitive(legacy)))
        } else {
            original
        }
        return ModelMessagesOrder.ordered(messages)
    }
}
