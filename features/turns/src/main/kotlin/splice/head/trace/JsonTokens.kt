// NEW: V4-343 — the lexical half of JsonLineShape: what kind each byte of a JSON line is, and the scalars
// (strings, numbers, literals) read a byte at a time after their first. A trace record's bytes are nearly all
// in strings, its bodies, so a string's run of plain bytes and whole escapes is passed eight bytes a step
// (ByteWords); after the first V4-343 commit that walk, a byte and a table look at a time, was 38% of the
// trace count on claudex's store, and array searches for escape letters another 10%.
package splice.head.trace

import splice.core.storage.ByteWords
import java.nio.ByteBuffer

// why: the hex digits a \u escape carries
private const val HEX_DIGITS = 4

// why: the last control character, which RFC 8259 admits in a string only escaped
private const val LAST_CONTROL = 0x1F

// why: the values a byte takes, read unsigned, for the byte tables below
private const val BYTE_VALUES = 256

private val QUOTE = '"'.code.toByte()
private val BACKSLASH = '\\'.code.toByte()
private val MINUS_SIGN = '-'.code.toByte()
private val PLUS_SIGN = '+'.code.toByte()
private val POINT_SIGN = '.'.code.toByte()
private val DIGIT_ZERO = '0'.code.toByte()
private val DIGIT_NINE = '9'.code.toByte()
private val LOWER_E = 'e'.code.toByte()
private val UPPER_E = 'E'.code.toByte()
private val LOWER_U = 'u'.code.toByte()
private val SIMPLE_ESCAPES = "\"\\/bfnrt".toByteArray()
private val HEX = "0123456789abcdefABCDEF".toByteArray()
private val LITERALS = listOf("true", "false", "null").map { it.toByteArray() }
private val QUOTES = ByteWords.repeated(QUOTE)
private val BACKSLASHES = ByteWords.repeated(BACKSLASH)
private val PRINTABLES = ByteWords.repeated((LAST_CONTROL + 1).toByte())

/** A scalar being read after its first byte: a string (a value's or a key's), a number or a literal. */
internal sealed class Token {
    abstract fun feed(b: Byte): Fed

    /** How a scalar took a byte: it goes on, the byte ended it, it ended just before the byte (which is the
     *  grammar's, not its own), or the byte cannot come here. */
    internal enum class Fed { MORE, DONE, BEFORE, BAD }

    /** A string after its opening quote. [escapes] is false for a top-level key, where an escape could spell a
     *  named member under another spelling. */
    internal class Text(private val escapes: Boolean) : Token() {
        private var escaping = false
        private var hexLeft = 0

        /** Not inside an escape, so its next bytes matter only when one is a quote, a backslash or a control. */
        val plain: Boolean get() = !escaping && hexLeft == 0

        override fun feed(b: Byte): Fed = when {
            hexLeft > 0 -> hexDigit(b)
            escaping -> escapeLetter(b)
            b == QUOTE -> Fed.DONE
            b == BACKSLASH -> backslash()
            ByteKinds.control(b) -> Fed.BAD
            else -> Fed.MORE
        }

        private fun backslash(): Fed {
            escaping = escapes
            return if (escapes) Fed.MORE else Fed.BAD
        }

        private fun escapeLetter(b: Byte): Fed {
            escaping = false
            if (b == LOWER_U) hexLeft = HEX_DIGITS
            return if (b == LOWER_U || ByteKinds.escape(b)) Fed.MORE else Fed.BAD
        }

        private fun hexDigit(b: Byte): Fed {
            hexLeft -= 1
            return if (ByteKinds.hex(b)) Fed.MORE else Fed.BAD
        }
    }

    /** A number after its first byte, [first]; it ends at the first byte that does not continue it. */
    internal class Numeric(first: Byte) : Token() {
        private var digits = when (first) {
            MINUS_SIGN -> Digits.SIGN
            DIGIT_ZERO -> Digits.ZERO
            else -> Digits.INTEGER
        }

        override fun feed(b: Byte): Fed {
            val next = digits.next(b) ?: return if (digits.complete) Fed.BEFORE else Fed.BAD
            digits = next
            return Fed.MORE
        }
    }

    /** `true`, `false` or `null` after its first byte. */
    internal class Literal(private val word: ByteArray) : Token() {
        private var at = 1

        override fun feed(b: Byte): Fed {
            if (b != word[at]) return Fed.BAD
            at += 1
            return if (at == word.size) Fed.DONE else Fed.MORE
        }
    }

    /** Where a number is in `-?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)?`, and where each byte moves it. */
    private enum class Digits(val complete: Boolean) {
        SIGN(false) {
            override fun next(b: Byte): Digits? = if (b == DIGIT_ZERO) ZERO else INTEGER.takeIf { ByteKinds.digit(b) }
        },
        ZERO(true) {
            override fun next(b: Byte): Digits? = afterInteger(b)
        },
        INTEGER(true) {
            override fun next(b: Byte): Digits? = if (ByteKinds.digit(b)) INTEGER else afterInteger(b)
        },
        POINT(false) {
            override fun next(b: Byte): Digits? = FRACTION.takeIf { ByteKinds.digit(b) }
        },
        FRACTION(true) {
            override fun next(b: Byte): Digits? = if (ByteKinds.digit(b)) FRACTION else exponent(b)
        },
        MARK(false) {
            override fun next(b: Byte): Digits? =
                if (b == PLUS_SIGN || b == MINUS_SIGN) EXPONENT_SIGN else EXPONENT.takeIf { ByteKinds.digit(b) }
        },
        EXPONENT_SIGN(false) {
            override fun next(b: Byte): Digits? = EXPONENT.takeIf { ByteKinds.digit(b) }
        },
        EXPONENT(true) {
            override fun next(b: Byte): Digits? = EXPONENT.takeIf { ByteKinds.digit(b) }
        },
        ;

        /** Where [b] moves the number, or null when [b] does not continue it. */
        abstract fun next(b: Byte): Digits?

        protected fun afterInteger(b: Byte): Digits? = if (b == POINT_SIGN) POINT else exponent(b)

        protected fun exponent(b: Byte): Digits? = MARK.takeIf { b == LOWER_E || b == UPPER_E }
    }
}

/** The token a scalar is read with, chosen by its first byte. */
internal object Tokens {
    /** The token for a value whose first byte is [b], or null when no value starts with it. */
    fun value(b: Byte): Token? {
        val word = LITERALS.firstOrNull { it[0] == b }
        return when {
            b == QUOTE -> Token.Text(escapes = true)
            word != null -> Token.Literal(word)
            b == MINUS_SIGN || ByteKinds.digit(b) -> Token.Numeric(b)
            else -> null
        }
    }

    /** The token for a key [b] opens, or null when it opens none; [escapes] as [Token.Text] takes it. */
    fun key(b: Byte, escapes: Boolean): Token.Text? = if (b == QUOTE) Token.Text(escapes) else null
}

/** What kind of byte a byte is, off tables, so a string's bytes cost one look each, or an eighth of one. */
internal object ByteKinds {
    /** Bytes a string's walk passes without looking: all but a quote, a backslash and a control character. */
    private val plains = BooleanArray(BYTE_VALUES) { it > LAST_CONTROL && it != '"'.code && it != '\\'.code }
    private val spaces = BooleanArray(BYTE_VALUES) { it in listOf(' '.code, '\t'.code, '\n'.code, '\r'.code) }
    private val escapes = BooleanArray(BYTE_VALUES) { it.toByte() in SIMPLE_ESCAPES }
    private val hexes = BooleanArray(BYTE_VALUES) { it.toByte() in HEX }

    fun space(b: Byte): Boolean = spaces[b.toUByte().toInt()]

    fun digit(b: Byte): Boolean = b in DIGIT_ZERO..DIGIT_NINE

    fun control(b: Byte): Boolean = b in 0..LAST_CONTROL

    /** A letter that ends an escape on its own, as `\n` or `\"` do. */
    fun escape(b: Byte): Boolean = escapes[b.toUByte().toInt()]

    fun hex(b: Byte): Boolean = hexes[b.toUByte().toInt()]

    /** The first byte of [words] from [from] up to [n] that a string's walk must look at, or [n]. Plain bytes and
     *  whole escapes are passed here, so the walk stops only at a quote, a control character, or an escape it
     *  cannot pass whole: a malformed one, or one the end of this read cuts, which the walk reads a byte at a time. */
    fun stringUntil(words: ByteBuffer, from: Int, n: Int): Int {
        val bytes = words.array()
        var i = plainUntil(words, from, n)
        var past = escapeEnd(bytes, i, n)
        while (past > i) {
            i = plainUntil(words, past, n)
            past = escapeEnd(bytes, i, n)
        }
        return i
    }

    /** Plain bytes, eight a step to the first word holding one that stops a string, whose first such byte the word's
     *  mask names; the last few bytes of a read, short of a word, one at a time. */
    private fun plainUntil(words: ByteBuffer, from: Int, n: Int): Int {
        var i = from
        while (i + Long.SIZE_BYTES <= n) {
            val stops = stops(words.getLong(i))
            if (stops != 0L) return i + ByteWords.first(stops)
            i += Long.SIZE_BYTES
        }
        val bytes = words.array()
        while (i < n && plains[bytes[i].toUByte().toInt()]) i += 1
        return i
    }

    private fun stops(word: Long): Long =
        ByteWords.equal(word, QUOTES) or ByteWords.equal(word, BACKSLASHES) or ByteWords.below(word, PRINTABLES)

    /** Just past the whole, well-formed escape starting at [at], or [at] when no such escape starts there. */
    private fun escapeEnd(bytes: ByteArray, at: Int, n: Int): Int {
        if (at + 1 >= n || bytes[at] != BACKSLASH) return at
        val letter = bytes[at + 1]
        val end = if (letter == LOWER_U) at + 2 + HEX_DIGITS else at + 2
        val whole = end <= n && if (letter == LOWER_U) hexRun(bytes, at + 2, end) else escape(letter)
        return if (whole) end else at
    }

    private fun hexRun(bytes: ByteArray, from: Int, until: Int): Boolean {
        var i = from
        while (i < until && hex(bytes[i])) i += 1
        return i == until
    }
}
