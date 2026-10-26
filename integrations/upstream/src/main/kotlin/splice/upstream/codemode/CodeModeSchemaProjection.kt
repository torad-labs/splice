// PORT-OF: codex-rs/tools/src/json_schema/types.rs @ rust-v0.164.0 (serde-supported schema representation).
package splice.upstream.codemode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

/** Round trips ToolSpec parameters through Codex's typed subset; raw protocol schemas retain their full form. */
public object CodeModeSchemaProjection {
    public fun canonical(value: JsonElement): JsonObject? = project(CodeModeText.jsonValue(value))

    private fun project(value: JsonElement): JsonObject? {
        val source = source(value) ?: return null
        val result = LinkedHashMap<String, JsonElement>()
        for (key in SCHEMA_FIELDS) {
            val child = source[key]?.takeUnless { it == JsonNull } ?: continue
            val projected = if (key in NESTED_SCHEMA_FIELDS) nested(key, child) else scalar(key, child)
            result[key] = projected ?: return null
        }
        return JsonObject(result)
    }

    private fun source(value: JsonElement): JsonObject? {
        return when (value) {
            is JsonObject -> value
            is JsonArray -> if (value.size == SCHEMA_FIELDS.size) {
                JsonObject(
                    SCHEMA_FIELDS.zip(value).toMap(),
                )
            } else {
                null
            }
            is JsonPrimitive -> null
        }
    }

    private fun scalar(key: String, child: JsonElement): JsonElement? = when (key) {
        "\$ref", "description" -> child.takeIf(::string)
        "type" -> child.takeIf(::schemaType)
        "encrypted" -> child.takeIf(::boolean)
        "enum" -> child as? JsonArray
        "minItems" -> child.takeIf(::unsigned)
        "required" -> (child as? JsonArray)?.takeIf { it.all(::string) }
        else -> error("Unknown scalar catalog schema field")
    }

    private fun nested(key: String, child: JsonElement): JsonElement? = when (key) {
        "items" -> project(child)
        "properties", "\$defs", "definitions" -> fields(child)
        "anyOf", "oneOf", "allOf" -> variants(child)
        "additionalProperties" -> if (boolean(child)) child else project(child)
        else -> error("Unknown nested catalog schema field")
    }

    private fun schemaType(value: JsonElement): Boolean = type(value) || (value is JsonArray && value.all(::type))

    private fun fields(value: JsonElement): JsonObject? {
        val fields = value as? JsonObject ?: return null
        val result = LinkedHashMap<String, JsonElement>()
        for ((key, child) in fields.toSortedMap(CodeModeText.order)) result[key] = project(child) ?: return null
        return JsonObject(result)
    }

    private fun variants(value: JsonElement): JsonArray? {
        val values = value as? JsonArray ?: return null
        return JsonArray(values.map { project(it) ?: return null })
    }

    private fun string(value: JsonElement): Boolean = (value as? JsonPrimitive)?.isString == true
    private fun boolean(value: JsonElement): Boolean = value == JsonPrimitive(true) || value == JsonPrimitive(false)
    private fun type(value: JsonElement): Boolean = string(value) && JsonScalars.str(value) in SCHEMA_TYPES
    private fun unsigned(value: JsonElement): Boolean = (value as? JsonPrimitive)?.takeUnless { it.isString }
        ?.let(
            JsonScalars::str,
        )?.toBigIntegerOrNull()?.let { it.signum() >= 0 && it <= CodeModeUnsigned.maximum } == true
}

private val SCHEMA_TYPES = setOf("string", "number", "boolean", "integer", "object", "array", "null")
private val SCHEMA_FIELDS = listOf(
    "\$ref", "type", "description", "encrypted", "enum", "items", "minItems",
    "properties", "required", "additionalProperties", "anyOf", "oneOf", "allOf", "\$defs", "definitions",
)

private val NESTED_SCHEMA_FIELDS =
    setOf("items", "properties", "\$defs", "definitions", "anyOf", "oneOf", "allOf", "additionalProperties")
