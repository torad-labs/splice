// PORT-OF: rust-stemmers 1.2.0 algorithms/english.sbl, region and short-syllable primitives.
package splice.upstream.codemode.search

internal class SearchEnglishWord(var value: String) {
    val firstRegion: Int = when {
        value.startsWith("gener") -> "gener".length
        value.startsWith("commun") -> "commun".length
        value.startsWith("arsen") -> "arsen".length
        else -> region(0)
    }
    val secondRegion: Int = region(firstRegion)

    fun suffix(vararg endings: String): String? = endings.filter(value::endsWith).maxByOrNull(String::length)

    fun replace(suffix: String, replacement: String = "", region: Int = 0): Boolean {
        val start = value.length - suffix.length
        if (start < region || !value.endsWith(suffix)) return false
        value = value.substring(0, start) + replacement
        return true
    }

    private fun region(start: Int): Int {
        for (index in start + 1 until value.length) {
            if (vowel(value[index - 1]) && !vowel(value[index])) return index + 1
        }
        return value.length
    }

    fun shortSyllable(text: String = value): Boolean = when {
        text.length >= SHORT_SYLLABLE_LENGTH -> {
            val end = text.lastIndex
            !vowel(text[end - 2]) && vowel(text[end - 1]) && !vowel(text[end]) && text[end] !in "wxY"
        }
        text.length == 2 -> vowel(text[0]) && !vowel(text[1])
        else -> false
    }

    fun vowel(char: Char): Boolean = char in "aeiouy"
}

// The ordinary short syllable is consonant-vowel-consonant; the two-character start form is separate.
private const val SHORT_SYLLABLE_LENGTH = 3
