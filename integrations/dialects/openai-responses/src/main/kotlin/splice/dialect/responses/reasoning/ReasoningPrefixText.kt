package splice.dialect.responses.reasoning

private const val EMPTY_PREFIX_BODY = "<!-- -->"
private const val MAX_PREFIX_HEADER_CHARS = 200

/** The first delta, rather than item index, orders raw and summary prefixes awaiting presentation. */
internal class ReasoningPrefixSequence {
    private var next = 0L
    fun next(): Long = next++
}

internal data class HeldReasoningPrefix(val key: Int, val summary: Boolean, val text: ReasoningPrefixText)

/** Maintains trimmed bounds as new chars arrive; classification never copies an accumulated prefix. */
internal class ReasoningPrefixText(val order: Long) {
    private val text = StringBuilder()
    private val trimmed = PrefixTextBounds()
    private val body = PrefixTextBounds()
    private var boldClose = -1
    val length: Int get() = text.length
    private val startsBold: Boolean
        get() = trimmed.first >= 0 && length > trimmed.first + 1 &&
            text[trimmed.first] == '*' && text[trimmed.first + 1] == '*'
    val mayBeEmpty: Boolean get() = matches(trimmed, prefix = true)
    val readable: Boolean
        get() = trimmed.first >= 0 && !matches(trimmed, prefix = false) &&
            (!startsBold || boldClose < 0 || !matches(body, prefix = false))
    val mayBeHeaderOnly: Boolean
        get() = when {
            mayBeEmpty -> true
            length - trimmed.first > MAX_PREFIX_HEADER_CHARS -> false
            length - trimmed.first < 2 -> text[trimmed.first] == '*'
            !startsBold -> false
            boldClose <= trimmed.first + 2 -> true
            else -> matches(body, prefix = true)
        }

    fun append(delta: String) {
        val start = text.length
        text.append(delta)
        for (index in start until text.length) {
            trimmed.include(index, text[index])
            trackBoldClose(index)
            if (boldClose >= 0 && index > boldClose + 1) body.include(index, text[index])
        }
    }

    private fun trackBoldClose(index: Int) {
        if (boldClose >= 0 || !startsBold) return
        if (index < trimmed.first + "**".length + 1) return
        if (text[index - 1] == '*' && text[index] == '*') boldClose = index - 1
    }

    private fun matches(bounds: PrefixTextBounds, prefix: Boolean): Boolean {
        val count = bounds.length
        if (count > EMPTY_PREFIX_BODY.length) return false
        if (!prefix && count != EMPTY_PREFIX_BODY.length) return false
        return (0 until count).all { text[bounds.first + it] == EMPTY_PREFIX_BODY[it] }
    }

    override fun toString(): String = text.toString()
}

private class PrefixTextBounds {
    var first = -1
        private set
    private var last = -1
    val length: Int get() = if (first < 0) 0 else last - first + 1

    fun include(index: Int, char: Char) {
        if (char.isWhitespace()) return
        if (first < 0) first = index
        last = index
    }
}
