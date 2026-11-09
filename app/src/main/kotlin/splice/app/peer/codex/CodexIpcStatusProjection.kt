package splice.app.peer.codex

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken

private const val STATUS_TYPE = "type"
private const val STATUS_CHANGE = "change"
private const val STATUS_PATCHES = "patches"
private const val THREAD_RUNTIME_STATUS = "threadRuntimeStatus"

// why: status labels are short protocol metadata, not arbitrary conversation text.
private const val STATUS_MAX_TEXT = 256

// why: common status paths contain one or two segments, so avoid repeated growth for those paths.
private const val PATCH_PATH_INITIAL_CAPACITY = 2

// why: bound path parsing and prevent oversized paths from being projected as runtime status.
private const val PATCH_PATH_MAX_SEGMENTS = 8

/** Projects router fields and only the runtime-status portion of desktop stream updates. */
internal object CodexIpcStatusProjection {
    fun projectParams(parser: JsonParser, generator: JsonGenerator, token: JsonToken) {
        if (token != JsonToken.START_OBJECT) {
            CodexIpcProjection.skipValue(parser, token)
            return
        }
        generator.writeObjectFieldStart("params")
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val name = parser.currentName() ?: CodexIpcProjection.invalid("desktop IPC params had an unnamed field")
            val value = parser.nextToken() ?: CodexIpcProjection.invalid("desktop IPC params had an incomplete field")
            projectParam(parser, generator, name, value)
        }
        if (parser.currentToken() != JsonToken.END_OBJECT) {
            CodexIpcProjection.invalid(
                "desktop IPC params were incomplete",
            )
        }
        generator.writeEndObject()
    }

    private fun projectParam(parser: JsonParser, generator: JsonGenerator, name: String, token: JsonToken) {
        when (name) {
            "hostId", "conversationId", "clientId", "status" -> {
                CodexIpcProjection.scalarText(parser)?.let { generator.writeStringField(name, it) }
                    ?: CodexIpcProjection.skipValue(parser, token)
            }
            STATUS_CHANGE -> projectChange(parser, generator, token)
            else -> CodexIpcProjection.skipValue(parser, token)
        }
    }

    private fun projectChange(parser: JsonParser, generator: JsonGenerator, token: JsonToken) {
        if (token != JsonToken.START_OBJECT) {
            CodexIpcProjection.skipValue(parser, token)
            return
        }
        generator.writeObjectFieldStart(STATUS_CHANGE)
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val name = parser.currentName() ?: CodexIpcProjection.invalid("desktop IPC change had an unnamed field")
            val value = parser.nextToken() ?: CodexIpcProjection.invalid("desktop IPC change had an incomplete field")
            projectChangeField(parser, generator, name, value)
        }
        if (parser.currentToken() != JsonToken.END_OBJECT) {
            CodexIpcProjection.invalid(
                "desktop IPC change was incomplete",
            )
        }
        generator.writeEndObject()
    }

    private fun projectChangeField(parser: JsonParser, generator: JsonGenerator, name: String, token: JsonToken) {
        when (name) {
            STATUS_TYPE, "revision", "baseRevision" ->
                CodexIpcProjection.writeSmallScalar(parser, generator, name, token)
            "conversationState" -> projectConversationState(parser, generator, token)
            STATUS_PATCHES -> CodexIpcPatchProjection.projectPatches(parser, generator, token)
            else -> CodexIpcProjection.skipValue(parser, token)
        }
    }

    private fun projectConversationState(parser: JsonParser, generator: JsonGenerator, token: JsonToken) {
        if (token != JsonToken.START_OBJECT) {
            CodexIpcProjection.skipValue(parser, token)
            return
        }
        generator.writeObjectFieldStart("conversationState")
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val name = parser.currentName() ?: CodexIpcProjection.invalid(
                "desktop conversation state had an unnamed field",
            )
            val value = parser.nextToken() ?: CodexIpcProjection.invalid(
                "desktop conversation state had an incomplete field",
            )
            if (name == THREAD_RUNTIME_STATUS) {
                projectStatus(parser, generator, value)
            } else {
                CodexIpcProjection.skipValue(parser, value)
            }
        }
        if (parser.currentToken() != JsonToken.END_OBJECT) {
            CodexIpcProjection.invalid(
                "desktop conversation state was incomplete",
            )
        }
        generator.writeEndObject()
    }

    private fun projectStatus(parser: JsonParser, generator: JsonGenerator, token: JsonToken) {
        if (token != JsonToken.START_OBJECT) {
            CodexIpcProjection.skipValue(parser, token)
            return
        }
        generator.writeObjectFieldStart(THREAD_RUNTIME_STATUS)
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val name = parser.currentName() ?: CodexIpcProjection.invalid("desktop runtime status had an unnamed field")
            val value = parser.nextToken() ?: CodexIpcProjection.invalid(
                "desktop runtime status had an incomplete field",
            )
            if (name == STATUS_TYPE) {
                CodexIpcProjection.scalarText(parser)?.takeIf { it.length <= STATUS_MAX_TEXT }
                    ?.let { generator.writeStringField(STATUS_TYPE, it) }
                    ?: CodexIpcProjection.skipValue(parser, value)
            } else {
                CodexIpcProjection.skipValue(parser, value)
            }
        }
        if (parser.currentToken() != JsonToken.END_OBJECT) {
            CodexIpcProjection.invalid(
                "desktop runtime status was incomplete",
            )
        }
        generator.writeEndObject()
    }
}

/** Reads arbitrary-order patch objects while skipping unrelated patch values. */
internal object CodexIpcPatchProjection {
    fun projectPatches(parser: JsonParser, generator: JsonGenerator, token: JsonToken) {
        if (token != JsonToken.START_ARRAY) {
            CodexIpcProjection.skipValue(parser, token)
            return
        }
        generator.writeArrayFieldStart(STATUS_PATCHES)
        while (true) {
            val item = parser.nextToken() ?: CodexIpcProjection.invalid("desktop status patches were incomplete")
            if (item == JsonToken.END_ARRAY) break
            if (item == JsonToken.START_OBJECT) {
                projectPatch(parser, generator)
            } else {
                CodexIpcProjection.skipValue(parser, item)
            }
        }
        generator.writeEndArray()
    }

    private fun projectPatch(parser: JsonParser, generator: JsonGenerator) {
        val fields = PatchFields()
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val name = parser.currentName() ?: CodexIpcProjection.invalid("desktop patch had an unnamed field")
            val token = parser.nextToken() ?: CodexIpcProjection.invalid("desktop patch had an incomplete field")
            readPatchField(parser, name, token, fields)
        }
        if (parser.currentToken() != JsonToken.END_OBJECT) CodexIpcProjection.invalid("desktop patch was incomplete")
        val patchPath = fields.path ?: return
        if (!relevantStatusPath(patchPath)) return
        writePatch(generator, fields.operation, patchPath, fields.value)
    }

    private fun readPatchField(parser: JsonParser, name: String, token: JsonToken, fields: PatchFields) {
        when (name) {
            "op" -> {
                fields.operation = CodexIpcProjection.scalarText(parser)
                if (fields.operation == null) CodexIpcProjection.skipValue(parser, token)
            }
            "path" -> {
                fields.pathSeen = true
                fields.path = readPath(parser, token)
            }
            "value" -> fields.value = if (!fields.pathSeen || fields.path?.let(::relevantStatusPath) == true) {
                readPatchValue(parser, token)
            } else {
                CodexIpcProjection.skipValue(parser, token)
                PatchValue.Other
            }
            else -> CodexIpcProjection.skipValue(parser, token)
        }
    }

    private fun readPath(parser: JsonParser, token: JsonToken): List<String>? {
        if (token != JsonToken.START_ARRAY) {
            CodexIpcProjection.skipValue(parser, token)
            return null
        }
        val result = ArrayList<String>(PATCH_PATH_INITIAL_CAPACITY)
        var valid = true
        var ended = false
        while (!ended) {
            val next = parser.nextToken() ?: CodexIpcProjection.invalid("desktop patch path was incomplete")
            if (next == JsonToken.END_ARRAY) ended = true else valid = readPathSegment(parser, next, result) && valid
        }
        return result.takeIf { valid }
    }

    private fun readPathSegment(parser: JsonParser, token: JsonToken, path: MutableList<String>): Boolean {
        if (token != JsonToken.VALUE_STRING || path.size >= PATCH_PATH_MAX_SEGMENTS) {
            CodexIpcProjection.skipValue(parser, token)
            return false
        }
        val segment = CodexIpcProjection.scalarText(parser) ?: return false
        path.add(segment)
        return true
    }

    private fun readPatchValue(parser: JsonParser, token: JsonToken): PatchValue = when (token) {
        JsonToken.VALUE_STRING -> CodexIpcProjection.scalarText(parser)?.let(PatchValue::Text) ?: PatchValue.Other
        JsonToken.START_OBJECT -> readStatusValue(parser)
        JsonToken.NOT_AVAILABLE, JsonToken.END_OBJECT, JsonToken.START_ARRAY, JsonToken.END_ARRAY,
        JsonToken.FIELD_NAME, JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT,
        JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE, JsonToken.VALUE_NULL, JsonToken.VALUE_EMBEDDED_OBJECT,
        -> {
            CodexIpcProjection.skipValue(parser, token)
            PatchValue.Other
        }
    }

    private fun readStatusValue(parser: JsonParser): PatchValue.Status {
        var type: String? = null
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val name = parser.currentName() ?: CodexIpcProjection.invalid("desktop patch value had an unnamed field")
            val value = parser.nextToken() ?: CodexIpcProjection.invalid("desktop patch value was incomplete")
            if (name == STATUS_TYPE) {
                type = CodexIpcProjection.scalarText(parser)
                if (type == null) CodexIpcProjection.skipValue(parser, value)
            } else {
                CodexIpcProjection.skipValue(parser, value)
            }
        }
        if (parser.currentToken() != JsonToken.END_OBJECT) {
            CodexIpcProjection.invalid(
                "desktop patch value was incomplete",
            )
        }
        return PatchValue.Status(type)
    }

    private fun writePatch(
        generator: JsonGenerator,
        operation: String?,
        path: List<String>,
        value: PatchValue?,
    ) {
        if (operation == null) CodexIpcProjection.invalid("desktop runtime status patch had no operation")
        if (operation != "remove" && value == null) {
            CodexIpcProjection.invalid(
                "desktop runtime status patch had no bounded value",
            )
        }
        generator.writeStartObject()
        generator.writeStringField("op", operation)
        generator.writeArrayFieldStart("path")
        path.forEach(generator::writeString)
        generator.writeEndArray()
        if (operation != "remove") writeValue(generator, path, value)
        generator.writeEndObject()
    }

    private fun writeValue(generator: JsonGenerator, path: List<String>, value: PatchValue?) {
        when {
            path.size == 1 && value is PatchValue.Status -> {
                generator.writeObjectFieldStart("value")
                value.type?.let { generator.writeStringField(STATUS_TYPE, it) }
                generator.writeEndObject()
            }
            path.size == 2 && value is PatchValue.Text -> generator.writeStringField("value", value.value)
            else -> CodexIpcProjection.invalid("desktop runtime status patch value had an unsupported shape")
        }
    }

    private fun relevantStatusPath(path: List<String>): Boolean =
        path == listOf(THREAD_RUNTIME_STATUS) || path == listOf(THREAD_RUNTIME_STATUS, STATUS_TYPE)

    private sealed class PatchValue {
        data class Text(val value: String) : PatchValue()
        data class Status(val type: String?) : PatchValue()
        data object Other : PatchValue()
    }

    private class PatchFields {
        var operation: String? = null
        var path: List<String>? = null
        var pathSeen: Boolean = false
        var value: PatchValue? = null
    }
}
