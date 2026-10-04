// NEW: transcript indexing consumes structure, not payload strings, and reuses the page's message-count rules.
package splice.client.transcript

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A minimal record for PageAssembly: payload string values are never materialized by the index. */
internal class TranscriptRecordShape {
    private val factory: JsonFactory = JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxStringLength(TRANSCRIPT_LINE_BYTES).build(),
    ).build()

    fun read(bytes: ByteArray): JsonObject? = try {
        factory.createParser(bytes).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) return null
            val record = objectAt(parser, Place.RECORD)
            if (parser.nextToken() == null) record else null
        }
    } catch (_: JsonProcessingException) {
        null // An unparseable record has no messages, exactly as in the page assembly.
    }

    private enum class Place { RECORD, MESSAGE, BLOCK }

    private fun objectAt(parser: JsonParser, place: Place): JsonObject? {
        val fields = linkedMapOf<String, JsonElement>()
        var name = nextField(parser)
        while (name != null) {
            val value = value(parser, place, name)
            if (value == null) fields.remove(name) else fields[name] = value
            name = nextField(parser)
        }
        return if (parser.currentToken() == JsonToken.END_OBJECT) JsonObject(fields) else null
    }

    private fun nextField(parser: JsonParser): String? {
        if (parser.nextToken() != JsonToken.FIELD_NAME) return null
        return parser.currentName()?.takeIf { parser.nextToken() != null }
    }

    private fun value(parser: JsonParser, place: Place, name: String): JsonElement? = when (parser.currentToken()) {
        JsonToken.START_OBJECT -> objectValue(parser, place, name)
        JsonToken.START_ARRAY -> arrayValue(parser, place, name)
        JsonToken.VALUE_STRING -> string(parser, place, name)
        JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE ->
            scalar(parser, place, name)
        else -> null
    }

    private fun objectValue(parser: JsonParser, place: Place, name: String): JsonObject? =
        if (name == "message" && place == Place.RECORD) {
            objectAt(parser, Place.MESSAGE)
        } else {
            null.also { parser.skipChildren() }
        }

    private fun arrayValue(parser: JsonParser, place: Place, name: String): JsonArray? =
        if (name == "content" && place == Place.MESSAGE) {
            blocks(parser)
        } else {
            null.also { parser.skipChildren() }
        }

    private fun identity(place: Place, name: String): Boolean =
        name == "type" || (name == "id" && place == Place.MESSAGE)

    private fun scalar(parser: JsonParser, place: Place, name: String): JsonPrimitive? = when {
        name in flags && parser.currentToken() in booleans -> JsonPrimitive(
            parser.currentToken() == JsonToken.VALUE_TRUE,
        )
        identity(place, name) -> JsonPrimitive(parser.text)
        name == "text" && place == Place.BLOCK -> JsonPrimitive("_")
        else -> null
    }

    private fun string(parser: JsonParser, place: Place, name: String): JsonPrimitive? = when {
        identity(place, name) -> JsonPrimitive(parser.text)
        name == "text" && place == Place.BLOCK -> JsonPrimitive("_")
        name == "content" -> content(parser, place)
        else -> null
    }

    private fun content(parser: JsonParser, place: Place): JsonPrimitive? = when (place) {
        Place.MESSAGE -> JsonPrimitive("_")
        Place.RECORD -> JsonPrimitive(if (parser.text.isBlank()) "" else "_")
        Place.BLOCK -> null
    }

    private fun blocks(parser: JsonParser): JsonArray? {
        val blocks = mutableListOf<JsonElement>()
        while (true) {
            val token = parser.nextToken() ?: return null
            if (token == JsonToken.END_ARRAY) return JsonArray(blocks)
            if (token == JsonToken.START_OBJECT) {
                objectAt(parser, Place.BLOCK)?.let(blocks::add)
            } else {
                parser.skipChildren()
            }
        }
    }

    private val flags = setOf("isSidechain", "isApiErrorMessage", "isMeta", "isCompactSummary")
    private val booleans = setOf(JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE)
}
