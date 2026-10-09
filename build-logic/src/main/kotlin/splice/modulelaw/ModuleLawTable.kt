// Reads gradle/module-law.txt, the project-dependency table splice.module-law enforces. One module per line,
// `<module> -> <allowed project dependencies>`, with `#` comments and blank lines between them. A line of any other
// shape is refused by its number, so a typo in the table fails the build where it was made.
package splice.modulelaw

object ModuleLawTable {
    private val LINE = Regex("""(:[\w.-]+)\s*->((?:\s+:[\w.-]+)*)\s*""")

    /** Module path -> the project paths its MAIN configurations may depend on. [source] names the file in errors. */
    fun parse(text: String, source: String): Map<String, Set<String>> {
        val law = linkedMapOf<String, Set<String>>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val match = LINE.matchEntire(line) ?: error("$source:${index + 1}: not `<module> -> <modules>`: $line")
            val module = match.groupValues[1]
            check(module !in law) { "$source:${index + 1}: $module is listed twice" }
            law[module] = match.groupValues[2].trim().split(Regex("""\s+""")).filter(String::isNotEmpty).toSet()
        }
        return law
    }
}
