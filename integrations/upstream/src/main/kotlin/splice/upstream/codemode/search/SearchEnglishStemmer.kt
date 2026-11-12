// PORT-OF: rust-stemmers 1.2.0 algorithms/english.sbl (Snowball English / Porter2).
package splice.upstream.codemode.search

/** The upstream exceptions, prelude, region marking and ordered Snowball steps. */
internal object SearchEnglishStemmer {
    private val exceptions = mapOf(
        "skis" to "ski", "skies" to "sky", "dying" to "die", "lying" to "lie", "tying" to "tie",
        "idly" to "idl", "gently" to "gentl", "ugly" to "ugli", "early" to "earli", "only" to "onli",
        "singly" to "singl", "sky" to "sky", "news" to "news", "howe" to "howe", "atlas" to "atlas",
        "cosmos" to "cosmos", "bias" to "bias", "andes" to "andes",
    )
    private val afterPluralExceptions =
        setOf("inning", "outing", "canning", "herring", "earring", "proceed", "exceed", "succeed")

    fun stem(text: String): String {
        exceptions[text]?.let { return it }
        if (text.length < MINIMUM_STEM_LENGTH) return text
        val prepared = text.removePrefix("'").toCharArray()
        for (index in prepared.indices) {
            val leadingOrAfterVowel = index == 0 || prepared[index - 1] in "aeiouy"
            if (prepared[index] == 'y' && leadingOrAfterVowel) prepared[index] = 'Y'
        }
        val word = SearchEnglishWord(String(prepared))
        SearchEnglishInflections.plural(word)
        if (word.value !in afterPluralExceptions) {
            SearchEnglishInflections.past(word)
            SearchEnglishInflections.finalY(word)
            SearchEnglishDerivations.second(word)
            SearchEnglishDerivations.third(word)
            SearchEnglishDerivations.fourth(word)
            SearchEnglishDerivations.fifth(word)
        }
        return word.value.replace('Y', 'y')
    }
}

// Upstream skips the entire Snowball prelude and suffix program for words shorter than three characters.
private const val MINIMUM_STEM_LENGTH = 3
