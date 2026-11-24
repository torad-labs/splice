// NEW: terminal launch profiles (issue #791, comment §4). A profile's CLAUDE.md: the person's own, with the blocks
// `drop_blocks` names taken out. A block is the lines from an opening marker line to its closing one, both included,
// each marker alone on its line (spaces around it allowed):
//
//     <!-- name -->        ...  <!-- /name -->
//     <!-- BEGIN name -->  ...  <!-- END name -->
//     # BEGIN name         ...  # END name
//
// any opening form closing with any closing form of the same name. A block opened and never closed is kept whole and
// named, so a missing end never takes the rest of the file with it.
package splice.client.profile

internal class ProfileInstructions(markers: List<String>) {
    private val blocks = markers.filter(String::isNotBlank).distinct().map(::Marker)

    /** [text] with the dropped blocks taken out, and the markers opened there that never close. */
    fun stripped(text: String): Stripped {
        val ends = text.endsWith(MD_LINE_END)
        val lines = text.split(MD_LINE_END).let { if (ends) it.dropLast(1) else it }
        val pass = Pass()
        lines.forEach(pass::feed)
        val body = pass.finish()
        return Stripped(if (ends) body else body.removeSuffix(MD_LINE_END), listOfNotNull(pass.open?.name))
    }

    /** The file's text as the profile carries it, and each marker [unclosed] there. */
    data class Stripped(val text: String, val unclosed: List<String>)

    /** One read of the file, line by line: what is kept, and the block being read while one is open. */
    private inner class Pass {
        private val out = StringBuilder()
        private val held = StringBuilder()
        var open: Marker? = null
            private set

        fun feed(line: String) {
            val bare = line.trim()
            val current = open
            when {
                current == null && blocks.none { it.opens(bare) } -> out.append(line).append(MD_LINE_END)
                current == null -> {
                    open = blocks.first { it.opens(bare) }
                    held.append(line).append(MD_LINE_END)
                }
                current.closes(bare) -> {
                    open = null
                    held.clear()
                }
                else -> held.append(line).append(MD_LINE_END)
            }
        }

        /** What is kept, a block never closed included. */
        fun finish(): String = out.append(held).toString()
    }

    private class Marker(val name: String) {
        private val quoted = Regex.escape(name)
        private val open = Regex("""<!--\s*(?:BEGIN\s+)?$quoted\s*-->|#\s*BEGIN\s+$quoted""")
        private val close = Regex("""<!--\s*(?:/\s*|END\s+)$quoted\s*-->|#\s*END\s+$quoted""")

        fun opens(line: String): Boolean = open.matches(line)

        fun closes(line: String): Boolean = close.matches(line)
    }
}

private const val MD_LINE_END = "\n"
