// NEW: the one rule file that says which tracked paths a text-scanning law reads, parsed by the build and by the law itself.
//
// A law that reads "every text file in the tree" must have those files fingerprinted as its task inputs, or a content-only edit to
// a file it reads is served from up-to-date. The rules live in ONE data file (lines `dir <name>`, `ext <extension>`,
// `prefix <repo-relative prefix>`; blank lines and `#` comments ignored). The build applies it to git's tracked list to declare
// the inputs; the law applies the same file to its fixture trees. A rule neither side can parse fails by line, never skips.
package splice.lawsuite

/** The parsed rules: [accepts] is true for a path none of them rejects. */
class CandidateRules(private val text: String) {
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
        check(dirs.isNotEmpty() && extensions.isNotEmpty() && prefixes.isNotEmpty()) {
            "candidate rules must hold at least one dir, one ext and one prefix rule"
        }
    }

    /** True when [relative] (forward slashes) is a path the law reads: none of the rules rejects it. */
    fun accepts(relative: String): Boolean {
        val extension = relative.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return relative.split('/').none { it in dirs } && extension !in extensions && prefixes.none { relative.startsWith(it) }
    }
}
