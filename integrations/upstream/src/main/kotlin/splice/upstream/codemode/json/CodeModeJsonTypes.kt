package splice.upstream.codemode.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import java.math.BigInteger

internal object CodeModeJsonTypes {
    fun unsigned(value: JsonElement, reader: CodeModeArgumentJson, expected: String): BigInteger {
        val primitive = value as? JsonPrimitive
        val text = primitive?.let(JsonScalars::str)
        val number = text?.takeUnless { primitive.isString }?.toBigIntegerOrNull()
        if (number != null && number.signum() < 0) reader.fail("invalid value: integer `$number`, expected $expected")
        if (number == null || number > UNSIGNED_MAX) reader.invalid(value, expected)
        return number
    }

    fun unexpected(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonObject -> "map"
        is JsonArray -> "sequence"
        is JsonPrimitive -> when {
            value.isString -> "string ${CodeModeRustDebug.string(value.content)}"
            value.content in listOf("true", "false") -> "boolean `${value.content}`"
            value.content.toBigIntegerOrNull() != null -> "integer `${value.content}`"
            else -> "floating point `${CodeModeJsonFloat.format(value.content.toDouble())}`"
        }
    }
}

private val UNSIGNED_MAX = splice.upstream.codemode.CodeModeUnsigned.maximum
