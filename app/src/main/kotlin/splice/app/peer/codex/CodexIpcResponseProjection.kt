package splice.app.peer.codex

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken

/** Copies bounded request results while avoiding retention of unrelated IPC response fields. */
internal object CodexIpcResponseProjection {
    fun writeResult(parser: JsonParser, generator: JsonGenerator, token: JsonToken, frameBytes: Long) {
        if (frameBytes > IPC_MAX_RESPONSE_RESULT_BYTES) {
            CodexIpcProjection.skipValue(parser, token)
            return
        }
        generator.writeFieldName("result")
        copyResult(parser, generator, token, 0)
    }

    private fun copyResult(parser: JsonParser, generator: JsonGenerator, token: JsonToken, depth: Int) {
        if (depth > IPC_MAX_NESTING) CodexIpcProjection.invalid("desktop IPC result was nested too deeply")
        when (token) {
            JsonToken.START_OBJECT -> copyObject(parser, generator, depth)
            JsonToken.START_ARRAY -> copyArray(parser, generator, depth)
            JsonToken.VALUE_STRING, JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT,
            JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE, JsonToken.VALUE_NULL,
            -> copyScalar(parser, generator, token)
            JsonToken.NOT_AVAILABLE, JsonToken.END_OBJECT, JsonToken.END_ARRAY, JsonToken.FIELD_NAME,
            JsonToken.VALUE_EMBEDDED_OBJECT,
            -> CodexIpcProjection.invalid("desktop IPC result contained an invalid JSON value")
        }
    }

    private fun copyObject(parser: JsonParser, generator: JsonGenerator, depth: Int) {
        generator.writeStartObject()
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val field = parser.currentName() ?: CodexIpcProjection.invalid("desktop IPC result had an unnamed field")
            generator.writeFieldName(field)
            val child = parser.nextToken() ?: CodexIpcProjection.invalid("desktop IPC result was incomplete")
            copyResult(parser, generator, child, depth + 1)
        }
        if (parser.currentToken() != JsonToken.END_OBJECT) {
            CodexIpcProjection.invalid(
                "desktop IPC result was incomplete",
            )
        }
        generator.writeEndObject()
    }

    private fun copyArray(parser: JsonParser, generator: JsonGenerator, depth: Int) {
        generator.writeStartArray()
        while (true) {
            val child = parser.nextToken() ?: CodexIpcProjection.invalid("desktop IPC result was incomplete")
            if (child == JsonToken.END_ARRAY) break
            copyResult(parser, generator, child, depth + 1)
        }
        generator.writeEndArray()
    }

    private fun copyScalar(parser: JsonParser, generator: JsonGenerator, token: JsonToken) {
        when (token) {
            JsonToken.VALUE_STRING -> generator.writeString(
                CodexIpcProjection.scalarText(parser)
                    ?: CodexIpcProjection.invalid("desktop IPC result string was invalid"),
            )
            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> generator.writeNumber(parser.text)
            JsonToken.VALUE_TRUE -> generator.writeBoolean(true)
            JsonToken.VALUE_FALSE -> generator.writeBoolean(false)
            JsonToken.VALUE_NULL -> generator.writeNull()
            JsonToken.NOT_AVAILABLE, JsonToken.START_OBJECT, JsonToken.END_OBJECT,
            JsonToken.START_ARRAY, JsonToken.END_ARRAY, JsonToken.FIELD_NAME, JsonToken.VALUE_EMBEDDED_OBJECT,
            -> CodexIpcProjection.invalid("desktop IPC result contained an invalid scalar")
        }
    }
}
