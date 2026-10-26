// PORT-OF: serde_json 1.0.149 Deserializer::ignore_value used by derived unknown-field visitors.
package splice.upstream.codemode.json

/** Ignored values validate syntax without imposing Value's depth bound or decoding surrogates. */
internal object CodeModeIgnoredJson {
    fun read(cursor: CodeModeJsonCursor) = IgnoredJsonVisitor(cursor).read()
}

private class IgnoredJsonVisitor(private val reader: CodeModeJsonCursor) {
    private val syntax = CodeModeJsonSyntax(reader)
    private enum class Frame {
        OBJECT_KEY, OBJECT_AFTER_COMMA, OBJECT_COLON, OBJECT_VALUE, OBJECT_END,
        ARRAY_VALUE, ARRAY_AFTER_COMMA, ARRAY_END,
    }
    private val stack = ArrayDeque<Frame>()
    private var value = true

    fun read() {
        while (value || stack.isNotEmpty()) {
            if (value) readValue() else readFrame()
        }
    }

    private fun readFrame() {
        when (stack.last()) {
            Frame.OBJECT_KEY, Frame.OBJECT_AFTER_COMMA -> objectKey()
            Frame.OBJECT_COLON -> colon()
            Frame.OBJECT_VALUE -> { replace(Frame.OBJECT_END); value = true }
            Frame.OBJECT_END -> objectEnd()
            Frame.ARRAY_VALUE, Frame.ARRAY_AFTER_COMMA -> arrayValue()
            Frame.ARRAY_END -> arrayEnd()
        }
    }

    private fun readValue() {
        when (reader.peek()) {
            '{' -> { reader.take(); stack.addLast(Frame.OBJECT_KEY) }
            '[' -> { reader.take(); stack.addLast(Frame.ARRAY_VALUE) }
            '"' -> syntax.string(CodeModeStringUse.IGNORED)
            else -> syntax.ignoredScalar()
        }
        value = false
    }

    private fun objectKey() {
        val afterComma = stack.last() == Frame.OBJECT_AFTER_COMMA
        when (reader.peek()) {
            '}' -> if (afterComma) fail("trailing comma") else finish()
            '"' -> { syntax.string(CodeModeStringUse.IGNORED); replace(Frame.OBJECT_COLON) }
            null -> reader.fail(if (afterComma) "EOF while parsing a value" else "EOF while parsing an object")
            else -> fail("key must be a string")
        }
    }

    private fun colon() {
        if (reader.peek() != ':') fail(if (reader.peek() == null) "EOF while parsing an object" else "expected `:`")
        reader.take(); replace(Frame.OBJECT_VALUE)
    }

    private fun objectEnd() = when (reader.peek()) {
        '}' -> finish()
        ',' -> { reader.take(); replace(Frame.OBJECT_AFTER_COMMA) }
        null -> reader.fail("EOF while parsing an object")
        else -> fail("expected `,` or `}`")
    }

    private fun arrayValue() {
        val afterComma = stack.last() == Frame.ARRAY_AFTER_COMMA
        when (reader.peek()) {
            ']' -> if (afterComma) fail("trailing comma") else finish()
            null -> reader.fail(if (afterComma) "EOF while parsing a value" else "EOF while parsing a list")
            else -> { replace(Frame.ARRAY_END); value = true }
        }
    }

    private fun arrayEnd() = when (reader.peek()) {
        ']' -> finish()
        ',' -> { reader.take(); replace(Frame.ARRAY_AFTER_COMMA) }
        null -> reader.fail("EOF while parsing a list")
        else -> fail("expected `,` or `]`")
    }

    private fun replace(frame: Frame) { stack[stack.lastIndex] = frame }
    private fun finish() { reader.take(); stack.removeLast() }
    private fun fail(reason: String): Nothing = reader.fail(reason, reader.nextOffset())
}
