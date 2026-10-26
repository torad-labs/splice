// PORT-OF: rust-stemmers 1.2.0 algorithms/english.sbl, steps 1a through 1c.
package splice.upstream.codemode.search

internal object SearchEnglishInflections {
    fun plural(word: SearchEnglishWord) {
        word.suffix("'", "'s", "'s'")?.let { word.replace(it) }
        when (val suffix = word.suffix("sses", "ied", "ies", "us", "ss", "s")) {
            "sses" -> word.replace(suffix, "ss")
            "ied", "ies" -> word.replace(suffix, if (word.value.length - suffix.length >= 2) "i" else "ie")
            "s" -> if (word.value.dropLast(2).any(word::vowel)) word.replace(suffix)
            else -> Unit
        }
    }

    fun past(word: SearchEnglishWord) {
        val suffix = word.suffix("eed", "eedly", "ed", "edly", "ing", "ingly") ?: return
        if (suffix == "eed" || suffix == "eedly") {
            word.replace(suffix, "ee", word.firstRegion)
            return
        }
        val stem = word.value.dropLast(suffix.length)
        if (!stem.any(word::vowel)) return
        word.value = stem
        when {
            word.suffix("at", "bl", "iz") != null -> word.value += 'e'
            word.suffix("bb", "dd", "ff", "gg", "mm", "nn", "pp", "rr", "tt") != null -> word.value = stem.dropLast(1)
            word.firstRegion == stem.length && word.shortSyllable() -> word.value += 'e'
        }
    }

    fun finalY(word: SearchEnglishWord) {
        val value = word.value
        if (value.length <= 2) return
        if (value.last() in "yY" && !word.vowel(value[value.lastIndex - 1])) {
            word.value = value.dropLast(1) + 'i'
        }
    }
}
