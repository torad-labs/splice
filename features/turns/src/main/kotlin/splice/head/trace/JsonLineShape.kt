// NEW: V4-343 — is one line exactly one JSON object? Read as bytes, never decoded, for TraceTail's count of a
// trace store. kotlinx rebuilt every record's multi-megabyte body strings only to skip them, 62% of the
// console trace page's 21.4 s on claudex's store (2026-09-26); this walks the bytes once by RFC 8259's
// grammar and keeps only the raw text of the few top-level members its reader names.
//
// It is a second author of "this line is JSON", beside kotlinx, so it is strict on purpose and answers only
// for lines kotlinx also parses: a line that is not strictly one object, or whose named members it cannot
// vouch for (one repeated, one holding an object or array, one past MAX_CAPTURE_BYTES, or any top-level key
// written with an escape, which could decode to a named one), is DECLINED, and the reader asks kotlinx as it
// always did. V4-343's fuzz cell holds that direction on thousands of damaged records.
package splice.head.trace

import java.io.InputStream

// why: the bytes read per call, a few pages: a 2-3 MB record streams through a buffer fifty times smaller
private const val READ_BYTES = 64 * 1024

// why: the longest named member kept; a turn id, a session or a timestamp is tens of bytes, and a longer one
// is no record this store's writer made, so it is declined rather than held
private const val MAX_CAPTURE_BYTES = 4096

// why: the nesting, and the captured bytes, the scan starts with room for; both grow past it
private const val INITIAL_ROOM = 16

// why: the hex digits a \u escape carries
private const val HEX_DIGITS = 4

// why: the last control character, which RFC 8259 admits in a string only escaped
private const val LAST_CONTROL = 0x1F

// why: the values a byte takes, read unsigned, for the byte tables below
private const val BYTE_VALUES = 256

private val QUOTE = '"'.code.toByte()
private val BACKSLASH = '\\'.code.toByte()
private val COLON = ':'.code.toByte()
private val COMMA = ','.code.toByte()
private val OPEN_OBJECT = '{'.code.toByte()
private val CLOSE_OBJECT = '}'.code.toByte()
private val OPEN_ARRAY = '['.code.toByte()
private val CLOSE_ARRAY = ']'.code.toByte()
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

/** Reads lines as JSON objects, keeping the raw text of the top-level members named [captured]. One per
 *  reader; its buffer is reused from line to line. */
internal class JsonLineShape(captured: Set<String>) {
    private val names = captured.map { it to it.toByteArray() }
    private val buffer = ByteArray(READ_BYTES)

    /** The raw text of each named member of the one JSON object [input] holds (a name it lacks is absent), or
     *  null when this cannot vouch for the line (see the header). Reads [input] until its end or the decline. */
    fun members(input: InputStream): Map<String, String>? {
        val scan = LineScan(names)
        var n = input.read(buffer)
        while (n >= 0) {
            if (!scan.feed(buffer, n)) return null
            n = input.read(buffer)
        }
        return scan.finish()
    }
}

/** What the next byte outside a scalar may be. */
private enum class Expect { VALUE, FIRST_ITEM, FIRST_KEY, KEY, COLON, AFTER, DONE }

/** A scalar being read after its first byte: a string (a value's or a key's), a number or a literal. */
private sealed class Token {
    abstract fun feed(b: Byte): Fed

    /** How a scalar took a byte: it goes on, the byte ended it, it ended just before the byte (which is the
     *  grammar's, not its own), or the byte cannot come here. */
    enum class Fed { MORE, DONE, BEFORE, BAD }

    /** A string after its opening quote. [escapes] is false for a top-level key, where an escape could spell a
     *  named member under another spelling. */
    class Text(private val escapes: Boolean) : Token() {
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
            return if (b == LOWER_U || b in SIMPLE_ESCAPES) Fed.MORE else Fed.BAD
        }

        private fun hexDigit(b: Byte): Fed {
            hexLeft -= 1
            return if (b in HEX) Fed.MORE else Fed.BAD
        }
    }

    /** A number after its first byte, [first]; it ends at the first byte that does not continue it. */
    class Numeric(first: Byte) : Token() {
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
    class Literal(private val word: ByteArray) : Token() {
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

/** What kind of byte a byte is, off tables, so a string's bytes cost one look each. */
private object ByteKinds {
    /** Bytes a string's walk passes without looking: all but a quote, a backslash and a control character. */
    private val plains = BooleanArray(BYTE_VALUES) { it > LAST_CONTROL && it != '"'.code && it != '\\'.code }
    private val spaces = BooleanArray(BYTE_VALUES) { it in listOf(' '.code, '\t'.code, '\n'.code, '\r'.code) }

    fun space(b: Byte): Boolean = spaces[b.toUByte().toInt()]

    fun digit(b: Byte): Boolean = b in DIGIT_ZERO..DIGIT_NINE

    fun control(b: Byte): Boolean = b in 0..LAST_CONTROL

    /** The first byte from [from] up to [n] that a string's walk must look at, or [n]. */
    fun plainUntil(bytes: ByteArray, from: Int, n: Int): Int {
        var i = from
        while (i < n && plains[bytes[i].toUByte().toInt()]) i += 1
        return i
    }
}

/** The containers the walk is inside, innermost last. */
private class Containers {
    private var stack = ByteArray(INITIAL_ROOM)

    var depth = 0
        private set

    val top: Byte get() = stack[depth - 1]

    /** Opens a container with [opener]; what its first byte may be. */
    fun open(opener: Byte): Expect {
        if (depth == stack.size) stack = stack.copyOf(stack.size * 2)
        stack[depth] = opener
        depth += 1
        return if (opener == OPEN_OBJECT) Expect.FIRST_KEY else Expect.FIRST_ITEM
    }

    /** Closes the innermost container when [opener] opened it: what may follow, or null when it did not. */
    fun close(opener: Byte): Expect? {
        if (depth == 0 || stack[depth - 1] != opener) return null
        depth -= 1
        return if (depth == 0) Expect.DONE else Expect.AFTER
    }
}

/** The named members at the top of the object: each top-level key as it is read, and the raw value of every
 *  one that names a member. */
private class Members(private val names: List<Pair<String, ByteArray>>) {
    private val key = KeyBytes(names.maxOfOrNull { it.second.size } ?: 0)
    private var naming: String? = null
    private var capture: Capture? = null

    val found = LinkedHashMap<String, String>()

    val capturing: Boolean get() = capture != null

    fun keyStarts() = key.reset()

    /** A top-level key ended; false when it names a member already met, which kotlinx would read twice. */
    fun keyEnds(): Boolean {
        naming = key.name(names)
        return naming?.let { it !in found } ?: true
    }

    /** A value starts; when it is a named member's, its capture starts too. False when a named member holds an
     *  object or an array, which is no record's stamp. */
    fun valueStarts(container: Boolean): Boolean {
        val name = naming ?: return true
        naming = null
        capture = Capture(name)
        return !container
    }

    /** Keeps [b]: a top-level key's byte when [inTopKey], else a named member's value byte. False once that
     *  value is longer than a member is kept. */
    fun take(b: Byte, inTopKey: Boolean): Boolean {
        if (inTopKey) key.add(b)
        return inTopKey || capture?.add(b) ?: true
    }

    fun valueEnds() {
        capture?.let { found[it.name] = it.text() }
        capture = null
    }
}

/** One line's walk by the grammar: the scalar being read, the containers it is in, the named members met. */
private class LineScan(names: List<Pair<String, ByteArray>>) {
    private var expect = Expect.VALUE
    private val containers = Containers()
    private val members = Members(names)
    private var token: Token? = null
    private var keying = false

    private val inTopKey: Boolean get() = keying && containers.depth == 1

    /** Inside a string no one keeps and no escape, whose bytes need a look only at its end, an escape or a
     *  control character. */
    private val passing: Boolean get() = !keying && !members.capturing && (token as? Token.Text)?.plain == true

    /** Walks [n] bytes of [bytes]; false once the line is declined. */
    fun feed(bytes: ByteArray, n: Int): Boolean {
        var i = 0
        var going = true
        while (going && i < n) {
            if (passing) i = ByteKinds.plainUntil(bytes, i, n)
            going = i >= n || step(bytes[i])
            i += 1
        }
        return going
    }

    /** The named members, when the line ended right after its one object and whitespace. */
    fun finish(): Map<String, String>? = members.found.takeIf { expect == Expect.DONE }

    private fun step(b: Byte): Boolean {
        val reading = token ?: return ByteKinds.space(b) || structure(b)
        return when (reading.feed(b)) {
            Token.Fed.MORE -> members.take(b, inTopKey)
            Token.Fed.DONE -> ended(b)
            Token.Fed.BEFORE -> ended(null) && step(b)
            Token.Fed.BAD -> false
        }
    }

    /** The scalar ended, [last] its closing byte when it had one: a key waits for its colon (a top-level one
     *  may name a member), a value is done and what follows a value may come. */
    private fun ended(last: Byte?): Boolean {
        val wasKey = keying
        val top = inTopKey
        token = null
        keying = false
        if (wasKey) {
            expect = Expect.COLON
            return !top || members.keyEnds()
        }
        val kept = last == null || members.take(last, false)
        members.valueEnds()
        expect = Expect.AFTER
        return kept
    }

    private fun structure(b: Byte): Boolean = when (expect) {
        Expect.VALUE, Expect.FIRST_ITEM -> value(b)
        Expect.FIRST_KEY, Expect.KEY -> key(b)
        Expect.COLON -> colon(b)
        Expect.AFTER -> after(b)
        Expect.DONE -> false
    }

    /** A value's first byte. The line is one object; a value where a named member's belongs starts its capture. */
    private fun value(b: Byte): Boolean {
        if (expect == Expect.FIRST_ITEM && b == CLOSE_ARRAY) return close(OPEN_ARRAY)
        val container = b == OPEN_OBJECT || b == OPEN_ARRAY
        val admitted = if (containers.depth == 0) b == OPEN_OBJECT else members.valueStarts(container)
        return admitted && if (container) open(b) else scalar(b)
    }

    private fun open(opener: Byte): Boolean {
        expect = containers.open(opener)
        return true
    }

    private fun scalar(b: Byte): Boolean {
        val word = LITERALS.firstOrNull { it[0] == b }
        token = when {
            b == QUOTE -> Token.Text(escapes = true)
            word != null -> Token.Literal(word)
            b == MINUS_SIGN || ByteKinds.digit(b) -> Token.Numeric(b)
            else -> null
        }
        return token != null && members.take(b, false)
    }

    private fun key(b: Byte): Boolean {
        if (expect == Expect.FIRST_KEY && b == CLOSE_OBJECT) return close(OPEN_OBJECT)
        if (b != QUOTE) return false
        keying = true
        members.keyStarts()
        token = Token.Text(escapes = containers.depth != 1)
        return true
    }

    private fun colon(b: Byte): Boolean {
        if (b != COLON) return false
        expect = Expect.VALUE
        return true
    }

    private fun after(b: Byte): Boolean = when (b) {
        COMMA -> comma()
        CLOSE_OBJECT -> close(OPEN_OBJECT)
        CLOSE_ARRAY -> close(OPEN_ARRAY)
        else -> false
    }

    private fun comma(): Boolean {
        expect = if (containers.top == OPEN_OBJECT) Expect.KEY else Expect.VALUE
        return true
    }

    private fun close(opener: Byte): Boolean {
        val next = containers.close(opener) ?: return false
        expect = next
        return true
    }
}

/** A top-level key's bytes, as far as the longest named member reaches. */
private class KeyBytes(longest: Int) {
    private val bytes = ByteArray(longest + 1)
    private var length = 0

    fun reset() {
        length = 0
    }

    fun add(b: Byte) {
        if (length < bytes.size) bytes[length] = b
        length += 1
    }

    /** The named member this key is, if any. */
    fun name(names: List<Pair<String, ByteArray>>): String? =
        names.firstOrNull { (_, name) -> name.size == length && name.indices.all { name[it] == bytes[it] } }?.first
}

/** A named member's value as it is read: its raw bytes, up to [MAX_CAPTURE_BYTES]. */
private class Capture(val name: String) {
    private var bytes = ByteArray(INITIAL_ROOM)
    private var length = 0

    /** False once the value is longer than a named member is kept. */
    fun add(b: Byte): Boolean {
        if (length == MAX_CAPTURE_BYTES) return false
        if (length == bytes.size) bytes = bytes.copyOf(minOf(bytes.size * 2, MAX_CAPTURE_BYTES))
        bytes[length] = b
        length += 1
        return true
    }

    /** The value's text, decoded leniently as the line is: a malformed byte reads as U+FFFD. */
    fun text(): String = String(bytes, 0, length, Charsets.UTF_8)
}
