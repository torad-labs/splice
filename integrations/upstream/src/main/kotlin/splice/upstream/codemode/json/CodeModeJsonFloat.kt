package splice.upstream.codemode.json

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** The nearest shortest decimal that round-trips to f64, with serde_json/zmij's notation thresholds. */
internal object CodeModeJsonFloat {
    fun format(number: Double): String {
        if (number == 0.0) return if (number.toRawBits() < 0) "-0.0" else "0.0"
        val magnitude = kotlin.math.abs(number)
        val exact = BigDecimal(magnitude)
        val nearest = (1..MAX_F64_DIGITS).firstNotNullOf { precision -> shortest(exact, magnitude, precision) }
        val exponent = nearest.precision() - nearest.scale() - 1
        val value = if (exponent in PLAIN_FLOAT_EXPONENTS) {
            nearest.toPlainString().let { if ('.' in it) it else "$it.0" }
        } else {
            nearest.movePointLeft(exponent).toPlainString() + "e" + (if (exponent >= 0) "+" else "") + exponent
        }
        return if (number < 0) "-$value" else value
    }

    private fun shortest(exact: BigDecimal, number: Double, precision: Int): BigDecimal? {
        val nearest = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        val step = BigDecimal.ONE.scaleByPowerOfTen(exact.precision() - exact.scale() - precision)
        // A binary power has unequal neighboring intervals; the adjacent decimal may be the shortest.
        return listOf(nearest, nearest - step, nearest + step)
            .filter { it.signum() > 0 && it.toDouble().toRawBits() == number.toRawBits() }
            .minByOrNull { (it - exact).abs() }?.stripTrailingZeros()
    }
}

// why: 17 significant digits round-trip any f64; zmij 1.0.19, the float writer of the serde_json 1.0.149 Codex
// pins, sets the f64 MAX_DIGITS10 to 17 (src/traits.rs:22).
private const val MAX_F64_DIGITS = 17

// why: zmij 1.0.19 src/lib.rs:1009 writes an f64 in plain notation when its decimal exponent is in -5..=15.
private const val MIN_PLAIN_FLOAT_EXPONENT = -5

// why: the upper end of that -5..=15 plain-notation range (zmij 1.0.19 src/lib.rs:1009).
private const val MAX_PLAIN_FLOAT_EXPONENT = 15
private val PLAIN_FLOAT_EXPONENTS = MIN_PLAIN_FLOAT_EXPONENT..MAX_PLAIN_FLOAT_EXPONENT
