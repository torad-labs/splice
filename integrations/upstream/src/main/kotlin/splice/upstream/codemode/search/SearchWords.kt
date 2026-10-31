// PORT-OF: unicode-segmentation 1.13.2 word.rs, restricted to its ASCII input after deunicode.
package splice.upstream.codemode.search

/** Unicode word-boundary rules on normalized ASCII; keeps contractions and decimal punctuation. */
internal object SearchWords {
    fun split(text: String): List<String> {
        val words = ArrayList<String>()
        var start = 0
        for (index in text.indices) {
            if (!word(text[index]) && !joins(text, index)) {
                append(words, text, start, index)
                start = index + 1
            }
        }
        append(words, text, start, text.length)
        return words
    }

    private fun joins(text: String, index: Int): Boolean {
        val before = text.getOrNull(index - 1) ?: return false
        val after = text.getOrNull(index + 1) ?: return false
        val letters = bothLetters(before, after)
        val numbers = bothNumbers(before, after)
        return when (text[index]) {
            '\'', '.' -> letters || numbers
            ':' -> letters
            ',', ';' -> numbers
            else -> false
        }
    }

    private fun append(words: MutableList<String>, text: String, start: Int, end: Int) {
        val word = text.substring(start, end)
        if (word.any { letter(it) || it in '0'..'9' }) words.add(word)
    }

    private fun word(char: Char): Boolean = letter(char) || char in '0'..'9' || char == '_'

    private fun letter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

    private fun bothLetters(before: Char, after: Char): Boolean = letter(before) && letter(after)

    private fun bothNumbers(before: Char, after: Char): Boolean = before in '0'..'9' && after in '0'..'9'
}
