package splice.upstream.codemode.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger

/** Derived visitors and release Value parsing share one cursor while retaining their distinct number semantics. */
public class CodeModeArgumentJson internal constructor(source: String) {
    internal val cursor = CodeModeJsonCursor(source)
    private val syntax = CodeModeJsonSyntax(cursor)
    private val collections = CodeModeJsonCollections(cursor, syntax)
    private var depth = 0
    private var arbitraryNumber: JsonElement? = null

    public fun peek(): Char? = cursor.peek()
    public fun fail(reason: String): Nothing = cursor.fail(reason)
    public fun unsigned(
        value: JsonElement,
        expected: String,
    ): BigInteger = CodeModeJsonTypes.unsigned(value, this, expected)
    public fun ignoreValue(): Unit = CodeModeIgnoredJson.read(cursor)

    public fun objectFields(
        beforeField: CodeModeJsonFieldEncounter = CodeModeJsonFieldEncounter {
        },
        field: CodeModeJsonField,
    ): Unit =
        collections.objectFields(beforeField) { field.read(it); CodeModeFieldVisit.NEXT }

    public fun sequence(maximumElements: Int? = null, element: CodeModeJsonElement): Unit =
        collections.sequence(maximumElements, element)

    public fun visitorFailure(reason: String): Nothing {
        if (peek() == '}') cursor.take()
        fail(reason)
    }

    public fun invalid(value: JsonElement, expected: String): Nothing = fail(
        "invalid type: ${if (value === arbitraryNumber) {
            "number"
        } else {
            CodeModeJsonTypes.unexpected(
                value,
            )
        }}, expected $expected",
    )

    public fun value(expected: String? = null): JsonElement {
        val next = peek() ?: fail("EOF while parsing a value")
        checkExpected(next, expected)
        return if (next in "{[") container(next) else scalar(next, expected)
    }

    private fun checkExpected(next: Char, expected: String?) {
        if (expected == null || next !in "{[") return
        if (expected == "struct ExecWaitArgs") cursor.take()
        fail("invalid type: ${if (next == '{') "map" else "sequence"}, expected $expected")
    }

    private fun scalar(next: Char, expected: String?): JsonElement {
        if (next in NUMBER_STARTS) {
            val use = if (expected in UNSIGNED_TYPES) CodeModeNumberUse.UNSIGNED else CodeModeNumberUse.VALUE
            return syntax.number(use).also { number ->
                arbitraryNumber = number.takeIf { use == CodeModeNumberUse.VALUE && CodeModeJsonLexemes.arbitrary(it) }
            }
        }
        return syntax.scalar(next)
    }

    private fun container(next: Char): JsonElement {
        if (++depth >= JSON_RECURSION_LIMIT) cursor.fail("recursion limit exceeded", cursor.position + 1)
        val value = if (next == '{') {
            objectValue()
        } else {
            val items = ArrayList<JsonElement>()
            sequence { items += value() }
            JsonArray(items)
        }
        depth--
        return value
    }

    private fun objectValue(): JsonElement {
        val fields = LinkedHashMap<String, JsonElement>()
        var first = true
        var special: JsonElement? = null
        collections.objectFields(CodeModeJsonFieldEncounter {}) { name ->
            val token = name.takeIf { first && it in VALUE_TOKENS }
            first = false
            if (token == null) {
                fields[name] = value()
                CodeModeFieldVisit.NEXT
            } else {
                special = specialValue(token)
                CodeModeFieldVisit.LAST
            }
        }
        return special ?: JsonObject(fields)
    }

    private fun specialValue(token: String): JsonElement {
        val expected = if (token == NUMBER_VALUE_TOKEN) "string containing a number" else "raw value"
        val supplied = value(expected)
        val text = (supplied as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(supplied, expected)
        return if (token == NUMBER_VALUE_TOKEN) CodeModeJson.number(text) else CodeModeJson.parse(text)
    }
}

private const val JSON_RECURSION_LIMIT = 128
private const val NUMBER_VALUE_TOKEN = "\$serde_json::private::Number"
private const val RAW_VALUE_TOKEN = "\$serde_json::private::RawValue"
private val VALUE_TOKENS = setOf(NUMBER_VALUE_TOKEN, RAW_VALUE_TOKEN)
private val NUMBER_STARTS = ('0'..'9').toSet() + '-'
private val UNSIGNED_TYPES = setOf("u64", "usize")
