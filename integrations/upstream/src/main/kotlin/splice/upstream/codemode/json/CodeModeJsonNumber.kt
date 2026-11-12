// PORT-OF: serde_json 1.0.149 de.rs parse_integer/decimal/exponent and f64_from_parts (default features).
package splice.upstream.codemode.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import splice.upstream.codemode.CodeModeUnsigned
import java.math.BigInteger

/** serde_json's default decimal parser deliberately uses its u64 significand and f64 power table. */
internal class CodeModeJsonNumber(private val source: String, private val offset: Int) {
    private var cursor = if (source.startsWith('-')) 1 else 0
    private val positive = cursor == 0
    private var significand = BigInteger.ZERO
    private var exponent = 0
    private var floating = false

    fun value(): JsonPrimitive {
        integer()
        if (source.getOrNull(cursor) == '.') decimal()
        if (source.getOrNull(cursor) in listOf('e', 'E')) scientific()
        if (!floating) {
            if (positive) return JsonPrimitive(significand)
            if (significand.signum() != 0 && significand <= NEGATIVE_INTEGER_MAGNITUDE) {
                return JsonPrimitive(
                    significand.negate(),
                )
            }
        }
        return JSON_NUMBER.parseToJsonElement(CodeModeJsonFloat.format(parts())) as JsonPrimitive
    }

    private fun integer() {
        while (source.getOrNull(cursor) in '0'..'9') {
            val next = significand * BigInteger.TEN + BigInteger.valueOf((source[cursor] - '0').toLong())
            if (next > CodeModeUnsigned.maximum) {
                floating = true
                while (source.getOrNull(cursor) in '0'..'9') { cursor++; exponent++ }
                return
            }
            significand = next
            cursor++
        }
    }

    private fun decimal() {
        floating = true
        cursor++
        while (source.getOrNull(cursor) in '0'..'9') {
            val next = significand * BigInteger.TEN + BigInteger.valueOf((source[cursor] - '0').toLong())
            if (next > CodeModeUnsigned.maximum) {
                while (source.getOrNull(cursor) in '0'..'9') cursor++
                return
            }
            significand = next
            cursor++
            exponent--
        }
    }

    private fun scientific() {
        floating = true
        cursor++
        val positiveExponent = source.getOrNull(cursor) != '-'
        if (source.getOrNull(cursor) in listOf('+', '-')) cursor++
        var supplied = 0
        while (cursor < source.length) {
            val next = supplied.toLong() * BigInteger.TEN.toLong() + (source[cursor++] - '0')
            if (next > Int.MAX_VALUE) {
                if (positiveExponent && significand.signum() != 0) range(cursor)
                significand = BigInteger.ZERO
                exponent = 0
                return
            }
            supplied = next.toInt()
        }
        exponent = (exponent.toLong() + if (positiveExponent) supplied.toLong() else -supplied.toLong())
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun parts(): Double {
        var number = significand.toDouble()
        while (exponent < -MAX_DECIMAL_POWER && number != 0.0) {
            number /= POWERS_OF_TEN.last()
            exponent += POWERS_OF_TEN.lastIndex
        }
        if (number != 0.0 || exponent in -MAX_DECIMAL_POWER..MAX_DECIMAL_POWER) {
            val power = POWERS_OF_TEN.getOrNull(kotlin.math.abs(exponent)) ?: range(source.length)
            number = if (exponent >= 0) number * power else number / power
            if (!number.isFinite()) range(source.length)
        }
        return if (positive) number else -number
    }

    private fun range(position: Int): Nothing = throw CodeModeJsonFailure("number out of range", offset + position)
}

private val JSON_NUMBER = Json
private val NEGATIVE_INTEGER_MAGNITUDE = BigInteger.valueOf(Long.MIN_VALUE).negate()

// why: serde_json 1.0.149's default f64_from_parts (src/de.rs:639) uses POW10, 1e0 through 1e308 (src/de.rs:1307),
// and divides by 1e308 past it (src/de.rs:666).
private const val MAX_DECIMAL_POWER = 308
private val POWERS_OF_TEN = DoubleArray(MAX_DECIMAL_POWER + 1) { "1e$it".toDouble() }
