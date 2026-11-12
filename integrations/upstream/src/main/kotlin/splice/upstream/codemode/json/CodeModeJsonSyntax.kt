package splice.upstream.codemode.json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal enum class CodeModeNumberUse { VALUE, UNSIGNED, IGNORED }

/** Lexical JSON grammar is shared by Value, typed numeric and allocation-free ignored visitors. */
internal class CodeModeJsonSyntax(private val cursor: CodeModeJsonCursor) {
    private val source get() = cursor.source

    fun string(use: CodeModeStringUse = CodeModeStringUse.VALUE): String {
        val (value, end) = CodeModeJsonString.read(source, cursor.position, use)
        cursor.position = end
        return value
    }

    fun literal(text: String, value: JsonElement): JsonElement {
        for (character in text) {
            if (cursor.position == source.length) cursor.fail(JSON_EOF_VALUE)
            if (cursor.take() != character) {
                throw CodeModeJsonFailure(
                    "expected ident",
                    cursor.position,
                    CodeModeJsonBytes.offset(source, cursor.position - 1) + 1,
                )
            }
        }
        return value
    }

    fun scalar(next: Char): JsonElement = when (next) {
        '"' -> JsonPrimitive(string())
        't' -> literal("true", JsonPrimitive(true))
        'f' -> literal("false", JsonPrimitive(false))
        'n' -> literal("null", JsonNull)
        else -> cursor.fail("expected value", cursor.position + 1)
    }

    fun ignoredScalar() {
        when (cursor.peek()) {
            '-', in '0'..'9' -> number(CodeModeNumberUse.IGNORED)
            't' -> literal("true", JsonPrimitive(true))
            'f' -> literal("false", JsonPrimitive(false))
            'n' -> literal("null", JsonNull)
            null -> cursor.fail(JSON_EOF_VALUE)
            else -> cursor.fail("expected value", cursor.position + 1)
        }
    }

    fun number(use: CodeModeNumberUse): JsonPrimitive {
        val start = cursor.position
        if (source[cursor.position] == '-') cursor.position++
        integer()
        if (source.getOrNull(cursor.position) == '.') {
            cursor.position++
            digits()
        }
        exponent()
        val text = source.substring(start, cursor.position)
        return when (use) {
            CodeModeNumberUse.IGNORED -> JsonPrimitive(0)
            CodeModeNumberUse.UNSIGNED -> CodeModeJsonNumber(text, start).value()
            CodeModeNumberUse.VALUE -> CodeModeJsonLexemes.value(text)
        }
    }

    fun numberValue(): JsonPrimitive {
        val next = source.getOrNull(cursor.position) ?: cursor.fail(JSON_EOF_VALUE)
        if (next != '-' && next !in '0'..'9') cursor.fail(JSON_INVALID_NUMBER, cursor.position + 1)
        return number(CodeModeNumberUse.VALUE).also {
            if (cursor.position != source.length) cursor.fail(JSON_INVALID_NUMBER, cursor.position + 1)
        }
    }

    private fun integer() {
        if (cursor.position == source.length) cursor.fail(JSON_EOF_VALUE)
        if (source[cursor.position] == '0') {
            cursor.position++
            if (source.getOrNull(cursor.position) in '0'..'9') cursor.fail(JSON_INVALID_NUMBER, cursor.position + 1)
        } else {
            if (source[cursor.position] !in '1'..'9') cursor.fail(JSON_INVALID_NUMBER, cursor.position + 1)
            while (source.getOrNull(cursor.position) in '0'..'9') cursor.position++
        }
    }

    private fun digits() {
        if (source.getOrNull(cursor.position) !in '0'..'9') {
            val reason = if (cursor.position == source.length) JSON_EOF_VALUE else JSON_INVALID_NUMBER
            cursor.fail(reason, cursor.position + 1)
        }
        while (source.getOrNull(cursor.position) in '0'..'9') cursor.position++
    }

    private fun exponent() {
        if (source.getOrNull(cursor.position) !in listOf('e', 'E')) return
        cursor.position++
        if (source.getOrNull(cursor.position) in listOf('+', '-')) cursor.position++
        digits()
    }
}

private const val JSON_EOF_VALUE = "EOF while parsing a value"
private const val JSON_INVALID_NUMBER = "invalid number"
