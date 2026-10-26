package splice.upstream.codemode.json

import kotlinx.serialization.json.JsonElement
import splice.core.util.JsonWire
import java.math.BigInteger

/** Codex's release serde_json Value boundary: insertion order, arbitrary precision and safe parse errors. */
public object CodeModeJson {
    public fun parse(source: String): JsonElement = read(source) { it.value() }

    /** A byte slice keeps serde's syntax precedence and original byte columns for malformed UTF-8. */
    public fun parse(source: ByteArray): JsonElement = parse(CodeModeJsonBytes.decode(source))

    public fun normalize(value: JsonElement): JsonElement = parse(JsonWire.string(value))

    internal fun number(source: String): JsonElement {
        val cursor = CodeModeJsonCursor(source)
        return try {
            CodeModeJsonSyntax(cursor).numberValue()
        } catch (error: CodeModeJsonFailure) {
            throw CodeModeJsonException(cursor.message(error), error)
        }
    }

    /** Derived visitors share the Value reader while retaining their source field types. */
    public fun <T> read(source: String, visitor: CodeModeJsonVisitor<T>): T {
        val reader = CodeModeArgumentJson(source)
        return try {
            visitor.read(reader).also { reader.cursor.end() }
        } catch (error: CodeModeJsonFailure) {
            throw CodeModeJsonException(reader.cursor.message(error), error)
        }
    }

    public fun unsigned(value: JsonElement, expected: String): BigInteger = try {
        if (invalidUnsigned(value)) throw CodeModeJsonFailure("invalid number", 0)
        CodeModeJsonTypes.unsigned(value, CodeModeArgumentJson(""), expected)
    } catch (error: CodeModeJsonFailure) {
        throw CodeModeJsonException(error.reason, error)
    }

    private fun invalidUnsigned(value: JsonElement): Boolean {
        val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return false
        if (primitive.isString || primitive.content in listOf("true", "false", "null")) return false
        return !unsignedNumber(primitive)
    }

    private fun unsignedNumber(value: kotlinx.serialization.json.JsonPrimitive): Boolean =
        value.content.toBigIntegerOrNull()?.let {
            it.signum() >= 0 && it <= splice.upstream.codemode.CodeModeUnsigned.maximum
        } == true
}

/** The diagnostic contains only an authored reason and byte location, never the input text. */
public class CodeModeJsonException(public val diagnostic: String, cause: IllegalArgumentException) :
    IllegalArgumentException(diagnostic, cause)
