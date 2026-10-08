// NEW: the reader, inside the law, of the rule file the build also parses (build-logic splice.lawsuite.CandidateRules).
//
// quality/architecture/src/test/resources/conventional-candidate-rules.txt says which paths the conventional-type law does not
// read. The build applies it to git's tracked list to declare the law's inputs; the law applies the same file to its fixture
// trees. One data file, two parsers of a three-keyword format: a line either parser cannot read fails by number.
package splice.quality

internal class ConventionalRules(text: String) {
    private val dirs = mutableSetOf<String>()
    private val extensions = mutableSetOf<String>()
    private val prefixes = mutableListOf<String>()

    init {
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                val value = line.substringAfter(' ', "").trim()
                check(value.isNotEmpty()) { "candidate rules line ${index + 1} names no value: '$line'" }
                when (line.substringBefore(' ')) {
                    "dir" -> dirs += value
                    "ext" -> extensions += value.lowercase()
                    "prefix" -> prefixes += value
                    else -> error("candidate rules line ${index + 1} is not dir, ext or prefix: '$line'")
                }
            }
        }
    }

    fun accepts(relative: String): Boolean {
        val extension = relative.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return relative.split('/').none { it in dirs } && extension !in extensions && prefixes.none { relative.startsWith(it) }
    }
}
