// NEW: LAN share, round 14 of PR #419's review. Text that comes from outside this process, made fit for a terminal, a
// log line and a page: every character a terminal acts on or cannot show is left out, by Unicode class (Cc: C0, C1 and
// DEL; Cf: bidi overrides and zero-width marks; Zl and Zp; Cn, Co and Cs: what no font draws), and nothing else is
// changed. `splice sessions` reads Claude Code's registry through it, and `splice remote` what another machine says, so
// an escape sequence in either can neither redraw the screen a person decides on nor forge a line of the log.
package splice.core.util

/** Leaves out of a text every character a terminal acts on or cannot show. */
public object TerminalText {
    private val unprintable: Set<Int> = setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.UNASSIGNED.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.SURROGATE.toInt(),
    )

    /** [text] with every character a terminal acts on or cannot show left out. */
    public fun printable(text: String): String = buildString {
        text.codePoints().filter { Character.getType(it) !in unprintable }.forEach { appendCodePoint(it) }
    }

    /** [text] printable and at most [most] characters long: a line's worth, never half of a character. */
    public fun line(text: String, most: Int): String {
        val cut = printable(text).take(most)
        return if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
    }
}
