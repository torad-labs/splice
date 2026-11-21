// What the template mode said back in a session, kept in its store as `{"said": [...]}`.
package splice.modes.template

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.modes.api.ModeStore

/** Reads and appends to a session's `said` list. A store text that is not that shape reads as an empty list. */
internal class TemplateSaid {
    fun of(store: ModeStore, session: String): List<String> {
        val list = (parsed(store.read(session)) as? JsonObject)?.get("said") as? JsonArray ?: return emptyList()
        return list.mapNotNull { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content }
    }

    fun add(store: ModeStore, session: String, text: String) {
        val list = of(store, session) + text
        store.write(session, JsonObject(mapOf("said" to JsonArray(list.map(::JsonPrimitive)))).toString())
    }

    private fun parsed(text: String?): JsonElement? = try {
        text?.let(Json::parseToJsonElement)
    } catch (_: SerializationException) {
        null
    }
}
