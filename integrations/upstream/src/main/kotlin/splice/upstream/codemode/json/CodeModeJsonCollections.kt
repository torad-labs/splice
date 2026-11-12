package splice.upstream.codemode.json

internal enum class CodeModeFieldVisit { NEXT, LAST }
internal fun interface CodeModeObjectField { fun read(name: String): CodeModeFieldVisit }

/** Collections retain serde's distinction between visitor refusal, closing delimiter and trailing comma. */
internal class CodeModeJsonCollections(private val cursor: CodeModeJsonCursor, private val syntax: CodeModeJsonSyntax) {
    fun objectFields(before: CodeModeJsonFieldEncounter, field: CodeModeObjectField) {
        check(cursor.peek() == '{')
        cursor.take()
        if (cursor.peek() == '}') { cursor.take(); return }
        while (true) {
            val name = key()
            cursor.peek()
            before.encounter(name)
            colon()
            if (field.read(name) == CodeModeFieldVisit.LAST) { finishObject(); return }
            if (!nextObject()) return
        }
    }

    fun sequence(maximumElements: Int?, element: CodeModeJsonElement) {
        check(cursor.peek() == '[')
        cursor.take()
        if (cursor.peek() == ']') { cursor.take(); return }
        var index = 0
        while (true) {
            if (cursor.peek() == null) cursor.fail(EOF_LIST)
            element.read(index++)
            if (index == maximumElements) { finishSequence(); return }
            if (!nextArray()) return
        }
    }

    private fun key(): String {
        when (cursor.peek()) {
            null -> cursor.fail(EOF_OBJECT)
            '"' -> Unit
            else -> cursor.fail("key must be a string", cursor.position + 1)
        }
        return syntax.string()
    }

    private fun colon() {
        if (cursor.peek() != ':') {
            cursor.fail(if (cursor.peek() == null) EOF_OBJECT else "expected `:`", cursor.position + 1)
        }
        cursor.take()
    }

    private fun nextObject(): Boolean = when (cursor.peek()) {
        '}' -> { cursor.take(); false }
        ',' -> { cursor.take(); afterComma('}'); true }
        null -> cursor.fail(EOF_OBJECT)
        else -> cursor.fail(EXPECT_OBJECT_END, cursor.position + 1)
    }

    private fun nextArray(): Boolean = when (cursor.peek()) {
        ']' -> { cursor.take(); false }
        ',' -> { cursor.take(); afterComma(']'); true }
        null -> cursor.fail(EOF_LIST)
        else -> cursor.fail("expected `,` or `]`", cursor.position + 1)
    }

    private fun afterComma(end: Char) {
        if (cursor.peek() == end) cursor.fail(TRAILING_COMMA, cursor.position + 1)
        if (cursor.peek() == null) cursor.fail("EOF while parsing a value")
    }

    private fun finishSequence() {
        when (cursor.peek()) {
            ']' -> cursor.take()
            ',' -> {
                cursor.take()
                cursor.fail(if (cursor.peek() == ']') TRAILING_COMMA else "trailing characters", cursor.nextOffset())
            }
            null -> cursor.fail(EOF_LIST)
            else -> cursor.fail("trailing characters", cursor.nextOffset())
        }
    }

    private fun finishObject() {
        when (cursor.peek()) {
            '}' -> cursor.take()
            ',' -> cursor.fail(TRAILING_COMMA, cursor.position + 1)
            null -> cursor.fail(EOF_OBJECT)
            else -> cursor.fail(EXPECT_OBJECT_END, cursor.position + 1)
        }
    }
}

private const val EOF_OBJECT = "EOF while parsing an object"
private const val EOF_LIST = "EOF while parsing a list"
private const val TRAILING_COMMA = "trailing comma"
private const val EXPECT_OBJECT_END = "expected `,` or `}`"
