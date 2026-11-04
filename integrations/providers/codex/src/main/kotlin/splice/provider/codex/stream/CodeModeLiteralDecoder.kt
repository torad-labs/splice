// NEW: a JavaScript string literal's value, decoded as it streams in.
package splice.provider.codex.stream

/** Decodes the body of one JavaScript string literal opened by [quote] (", ' or a backtick) as the script streams it.
 *  It gives only text it is sure of: it waits at an escape cut between two deltas, holds for good at an
 *  interpolation or an escape whose value depends on the script's mode, and never gives the first half of a
 *  surrogate pair without its second. */
internal class CodeModeLiteralDecoder(private val quote: Char) {
    enum class State { OPEN, CLOSED, HELD }

    private sealed class Step {
        data class Took(val text: String, val width: Int) : Step()
        data object More : Step()
        data object Hold : Step()
        data object End : Step()
    }

    var state = State.OPEN
        private set
    private val pending = StringBuilder()
    private var carry = ""

    /** The text [chunk] completes; empty once the literal closed or held. */
    fun feed(chunk: String): String {
        if (state != State.OPEN) return ""
        pending.append(chunk)
        val out = StringBuilder(carry)
        carry = ""
        pending.delete(0, drain(out))
        settle(out)
        return out.toString()
    }

    /** Decodes [pending] into [out] until it ends, holds, or needs more; returns how much it consumed. */
    private fun drain(out: StringBuilder): Int {
        var at = 0
        while (at < pending.length && state == State.OPEN) {
            when (val step = step(at)) {
                is Step.Took -> {
                    out.append(step.text)
                    at += step.width
                }
                Step.More -> return at
                Step.Hold -> state = State.HELD
                Step.End -> state = State.CLOSED
            }
        }
        return at
    }

    /** A high surrogate at the end waits for its pair while the literal is open, and is dropped once it held. */
    private fun settle(out: StringBuilder) {
        if (state == State.CLOSED || out.lastOrNull()?.isHighSurrogate() != true) return
        if (state == State.OPEN) carry = out.last().toString()
        out.setLength(out.length - 1)
    }

    private fun step(at: Int): Step {
        val c = pending[at]
        return when {
            c == quote -> Step.End
            c == '\\' -> after(at) { e -> SIMPLE[e]?.let { Step.Took(it, ESCAPE) } ?: escape(at, e) }
            quote == '`' -> template(at, c)
            c == '\n' || c == '\r' -> Step.Hold
            else -> Step.Took(c.toString(), 1)
        }
    }

    /** A template's own characters: a raw line break reads as \n, and an interpolation holds. */
    private fun template(at: Int, c: Char): Step = when (c) {
        '$' -> after(at) { next -> if (next == '{') Step.Hold else Step.Took("$", 1) }
        '\r' -> after(at) { next -> Step.Took("\n", if (next == '\n') 2 else 1) }
        else -> Step.Took(c.toString(), 1)
    }

    /** An escape [SIMPLE] does not name: a NUL, a code point, a line continuation, or the character itself. */
    private fun escape(at: Int, e: Char): Step = when (e) {
        '0' -> after(at + 1) { next -> if (next in '0'..'9') Step.Hold else Step.Took("\u0000", ESCAPE) }
        in '1'..'9' -> Step.Hold
        'x' -> fixed(at + ESCAPE, HEX_DIGITS)
        'u' -> unicode(at)
        in LINE_BREAKS -> continuation(at, e)
        else -> Step.Took(e.toString(), ESCAPE)
    }

    /** A backslash before a line break continues the line: it reads as nothing, a CR LF pair taken whole. */
    private fun continuation(at: Int, e: Char): Step = if (e == '\r') {
        after(at + 1) { next -> Step.Took("", if (next == '\n') CRLF_CONTINUATION else ESCAPE) }
    } else {
        Step.Took("", ESCAPE)
    }

    private fun unicode(at: Int): Step {
        val open = at + ESCAPE
        return when {
            open >= pending.length -> Step.More
            pending[open] == '{' -> braced(at, open)
            else -> fixed(open, UNICODE_DIGITS)
        }
    }

    /** \u{...}: up to six hex digits naming a code point. */
    private fun braced(at: Int, open: Int): Step {
        val close = (open + 1 until minOf(pending.length, open + MAX_BRACED)).firstOrNull { pending[it] == '}' }
            ?: return if (pending.length < open + MAX_BRACED) Step.More else Step.Hold
        val digits = pending.substring(open + 1, close)
        val code = digits.toIntOrNull(HEX)?.takeIf { digits.isNotEmpty() && it <= Character.MAX_CODE_POINT }
        return code?.let { codeAt(it, width = close - at + 1) } ?: Step.Hold
    }

    /** [count] hex digits from [from], the escape's whole width ending with them; more while they may still come. */
    private fun fixed(from: Int, count: Int): Step {
        val end = from + count
        if (end > pending.length) {
            return if (pending.substring(from).all(::isHexDigit)) Step.More else Step.Hold
        }
        val digits = pending.substring(from, end)
        return if (digits.all(::isHexDigit)) codeAt(digits.toInt(HEX), width = count + ESCAPE) else Step.Hold
    }

    private fun codeAt(code: Int, width: Int): Step = Step.Took(String(Character.toChars(code)), width)

    /** Decides on the character after [at], or waits for it. */
    private inline fun after(at: Int, decide: (Char) -> Step): Step =
        if (at + 1 >= pending.length) Step.More else decide(pending[at + 1])

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}

/** The escapes that stand for one character each. */
private val SIMPLE = mapOf(
    'n' to "\n",
    't' to "\t",
    'r' to "\r",
    'b' to "\b",
    'f' to "\u000C",
    'v' to "\u000B",
)

/** The line terminators a backslash can continue a line across. */
private val LINE_BREAKS = setOf('\r', '\n', ' ', ' ')

private const val HEX = 16

/** A backslash and the character after it. */
private const val ESCAPE = 2

/** A backslash, a carriage return and a line feed: one line continuation. */
private const val CRLF_CONTINUATION = 3

private const val HEX_DIGITS = 2
private const val UNICODE_DIGITS = 4

/** A braced code point escape is at most \u{10FFFF}: the brace, six digits and the closing brace. */
private const val MAX_BRACED = 9
