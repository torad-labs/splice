package splice.app.wire

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.json.JsonReadFeature
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/** Tokenizes wire JSON without recursive descent or a settings-file nesting policy. */
internal object WireJson {
    private val decoder = factory().build()

    // The proxy need not normalize a payload to inspect its routing fields. The destination sees the original.
    private val inspection = factory().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build()

    private fun factory(): JsonFactoryBuilder = JsonFactoryBuilder()
        .disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES)
        .disable(JsonFactory.Feature.INTERN_FIELD_NAMES)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(Int.MAX_VALUE)
                .maxNumberLength(Int.MAX_VALUE)
                .maxStringLength(Int.MAX_VALUE)
                .maxNameLength(Int.MAX_VALUE)
                .build(),
        )

    /** Reads one complete value, preserving number spelling for the compact feed representation. */
    fun read(document: String): JsonElement? = try {
        decoder.createParser(document).use { parser ->
            if (parser.nextToken() == null) null else readValue(parser).takeIf { parser.nextToken() == null }
        }
    } catch (_: JsonProcessingException) {
        null
    }

    /** Reads selected root fields, skipping other values without building a tree for them. */
    fun fields(document: String, names: Set<String>): JsonObject? = try {
        inspection.createParser(document).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) null else readFields(parser, names)
        }
    } catch (_: JsonProcessingException) {
        null
    }

    private fun readFields(parser: JsonParser, names: Set<String>): JsonObject? {
        val fields = linkedMapOf<String, JsonElement>()
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val key = parser.currentName()
            parser.nextToken()
            if (key in names) {
                if (parser.currentToken()?.isScalarValue != true) return null
                fields[key] = scalar(parser)
            } else {
                parser.skipChildren()
            }
        }
        val ended = parser.currentToken() == JsonToken.END_OBJECT && parser.nextToken() == null
        return if (ended) JsonObject(fields) else null
    }

    private fun readValue(parser: JsonParser): JsonElement {
        val open = ArrayDeque<Container>()
        while (true) {
            val value = take(parser, open)
            if (value != null) {
                val parent = open.lastOrNull() ?: return value
                parent.add(value)
            }
            parser.nextToken()
        }
    }

    private fun take(parser: JsonParser, open: ArrayDeque<Container>): JsonElement? {
        val token = parser.currentToken()
        return when {
            token?.isStructStart == true -> {
                open.addLast(if (token == JsonToken.START_OBJECT) Members() else Items())
                null
            }
            token?.isStructEnd == true -> open.removeLast().finish()
            token == JsonToken.FIELD_NAME -> {
                readKey(parser, open.lastOrNull())
                null
            }
            else -> scalar(parser)
        }
    }

    private fun readKey(parser: JsonParser, container: Container?) {
        val objectFrame = container as? Members ?: throw JsonParseException(parser, "expected an object")
        objectFrame.key = parser.currentName()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun scalar(parser: JsonParser): JsonElement = when (parser.currentToken()) {
        JsonToken.VALUE_STRING -> JsonPrimitive(parser.text)
        JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> JsonUnquotedLiteral(parser.text)
        JsonToken.VALUE_TRUE -> JsonPrimitive(true)
        JsonToken.VALUE_FALSE -> JsonPrimitive(false)
        JsonToken.VALUE_NULL -> JsonNull
        JsonToken.START_OBJECT, JsonToken.END_OBJECT, JsonToken.START_ARRAY, JsonToken.END_ARRAY,
        JsonToken.FIELD_NAME, JsonToken.NOT_AVAILABLE, JsonToken.VALUE_EMBEDDED_OBJECT, null,
        -> throw JsonParseException(parser, "expected a JSON scalar")
    }

    private sealed class Container {
        abstract fun add(value: JsonElement)
        abstract fun finish(): JsonElement
    }

    private class Members : Container() {
        private val entries = linkedMapOf<String, JsonElement>()
        var key: String = ""

        override fun add(value: JsonElement) {
            entries[key] = value
        }

        override fun finish(): JsonObject = JsonObject(entries)
    }

    private class Items : Container() {
        private val entries = ArrayList<JsonElement>()

        override fun add(value: JsonElement) {
            entries.add(value)
        }

        override fun finish(): JsonArray = JsonArray(entries)
    }
}
