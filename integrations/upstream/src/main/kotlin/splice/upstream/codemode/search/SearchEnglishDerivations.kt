// PORT-OF: rust-stemmers 1.2.0 algorithms/english.sbl, steps 2 through 5.
package splice.upstream.codemode.search

internal object SearchEnglishDerivations {
    private val secondEndings = mapOf(
        "tional" to "tion", "enci" to "ence", "anci" to "ance", "abli" to "able", "entli" to "ent",
        "izer" to "ize", "ization" to "ize", "ational" to "ate", "ation" to "ate", "ator" to "ate",
        "alism" to "al", "aliti" to "al", "alli" to "al", "fulness" to "ful", "ousli" to "ous", "ousness" to "ous",
        "iveness" to "ive", "iviti" to "ive", "biliti" to "ble", "bli" to "ble", "ogi" to "og", "fulli" to "ful",
        "lessli" to "less", "li" to "",
    )
    private val thirdEndings = mapOf(
        "tional" to "tion", "ational" to "ate", "alize" to "al", "icate" to "ic", "iciti" to "ic", "ical" to "ic",
        "ful" to "", "ness" to "", "ative" to "",
    )
    private val fourthEndings = arrayOf(
        "al", "ance", "ence", "er", "ic", "able", "ible", "ant", "ement", "ment", "ent", "ism", "ate", "iti",
        "ous", "ive", "ize", "ion",
    )

    fun second(word: SearchEnglishWord) {
        val suffix = secondEndings.keys.filter(word.value::endsWith).maxByOrNull(String::length) ?: return
        val before = word.value.getOrNull(word.value.length - suffix.length - 1)
        if (suffix == "ogi" && before != 'l') return
        val validLi = before?.let { it in "cdeghkmnrt" } == true
        if (suffix == "li" && !validLi) return
        word.replace(suffix, secondEndings.getValue(suffix), word.firstRegion)
    }

    fun third(word: SearchEnglishWord) {
        val suffix = thirdEndings.keys.filter(word.value::endsWith).maxByOrNull(String::length) ?: return
        val region = if (suffix == "ative") word.secondRegion else word.firstRegion
        word.replace(suffix, thirdEndings.getValue(suffix), region)
    }

    fun fourth(word: SearchEnglishWord) {
        val suffix = fourthEndings.filter(word.value::endsWith).maxByOrNull(String::length) ?: return
        val before = word.value.getOrNull(word.value.length - suffix.length - 1)
        val validIon = before == 's' || before == 't'
        if (suffix == "ion" && !validIon) return
        word.replace(suffix, region = word.secondRegion)
    }

    fun fifth(word: SearchEnglishWord) {
        val start = word.value.lastIndex
        when {
            word.value.endsWith('e') && (
                start >= word.secondRegion ||
                    (start >= word.firstRegion && !word.shortSyllable(word.value.dropLast(1)))
                ) -> word.replace("e")
            word.value.endsWith("ll") && start >= word.secondRegion -> word.replace("l")
        }
    }
}
