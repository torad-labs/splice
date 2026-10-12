// NEW: Oct 11, 2026 — repeated descriptions (models, accounts, sessions) stored once and named by a small number.
package splice.app.sources

// A stored absent value.
internal const val STRING_NONE = 0

// The text follows in place: the table was full, or the text is too long to be worth keeping.
internal const val STRING_INLINE = 1

private const val FIRST_ID = 2

// Covers the map entry, the list slot and both references of one table entry.
private const val TABLE_ENTRY_BYTES = 64L

// why: a week of the busiest command names about eight thousand sessions; the rest of the table's room is for
// whatever else a month holds, and anything past it is written in place so the table never outgrows its charge.
private const val TABLE_ENTRIES = 32_768

// A text longer than this is not a repeated description (a failure sentence, a path), so it is written in place.
private const val TABLE_TEXT_CHARS = 128

/** Gives each distinct short text a number. The table only grows, to a bound, and charges what it holds. */
internal class PerfStringTable {
    private val numbers = HashMap<String, Int>()
    private val texts = ArrayList<String>()

    var retainedBytes = 0L
        private set

    /** The number for [text], or [STRING_INLINE] when it is not kept. */
    fun numberOf(text: String): Int {
        numbers[text]?.let { return it }
        if (texts.size >= TABLE_ENTRIES || text.length > TABLE_TEXT_CHARS) return STRING_INLINE
        val number = texts.size + FIRST_ID
        texts.add(text)
        numbers[text] = number
        retainedBytes += TABLE_ENTRY_BYTES + PERF_STRING_OVERHEAD_BYTES + text.length * PERF_CHAR_BYTES
        return number
    }

    fun textOf(number: Int): String = texts[number - FIRST_ID]
}
