// Terminal launch profiles (issue #791): a profile's CLAUDE.md is the person's own, less the blocks
// named by drop_blocks. Each marker stands alone on its line, with surrounding whitespace allowed:
//
//     <!-- name -->        ...  <!-- /name -->
//     <!-- BEGIN name -->  ...  <!-- END name -->
//     # BEGIN name         ...  # END name
//     <name …>             ...  </name>
//
// Comment and # BEGIN forms can close with any of their closing forms for the same name. XML tags
// close only with the matching XML end tag and can nest by name; a self-closing tag drops one line.
// A block that never closes is kept whole and named.
package splice.client.profile

internal class ProfileInstructions(markers: List<String>) {
    private val blocks = markers.filter(String::isNotBlank).distinct().map(::Marker)

    /** [text] with the dropped blocks taken out, and the markers opened there that never close. */
    fun stripped(text: String): Stripped {
        val pass = Pass()
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline + 1
            pass.feed(text.substring(start, end))
            start = end
        }
        return Stripped(pass.finish(), listOfNotNull(pass.open?.marker?.name))
    }

    /** The file's text as the profile carries it, and each marker [unclosed] there. */
    data class Stripped(val text: String, val unclosed: List<String>)

    /** One read of the file, line by line: what is kept, and the block being read while one is open. */
    private inner class Pass {
        private val out = StringBuilder()
        private val held = StringBuilder()
        var open: OpenBlock? = null
            private set

        fun feed(line: String) {
            val bare = line.trim()
            val current = open
            if (current == null) readOutsideBlock(line, bare) else readInsideBlock(current, line, bare)
        }

        private fun readOutsideBlock(line: String, bare: String) {
            val block = blocks.firstNotNullOfOrNull { it.opening(bare) }
            when {
                block == null -> out.append(line)
                !block.selfCloses(bare) -> {
                    open = block
                    held.append(line)
                }
            }
        }

        private fun readInsideBlock(block: OpenBlock, line: String, bare: String) {
            if (block.endsAt(bare)) {
                open = null
                held.clear()
            } else {
                held.append(line)
            }
        }

        /** What is kept, a block never closed included. */
        fun finish(): String = out.append(held).toString()
    }

    private enum class Family { COMMENT, XML }

    private class OpenBlock(val marker: Marker, private val family: Family) {
        private var depth = 1

        fun selfCloses(line: String): Boolean = family == Family.XML && line.endsWith("/>")

        fun endsAt(line: String): Boolean {
            when {
                marker.closes(line, family) -> depth--
                family == Family.XML && !selfCloses(line) && marker.opensXml(line) -> depth++
            }
            return depth == 0
        }
    }

    private class Marker(val name: String) {
        private val quoted = Regex.escape(name)
        private val commentOpen = Regex("""<!--\s*(?:BEGIN\s+)?$quoted\s*-->|#\s*BEGIN\s+$quoted""")
        private val commentClose = Regex("""<!--\s*(?:/\s*|END\s+)$quoted\s*-->|#\s*END\s+$quoted""")
        private val xmlTag = Regex("""<$quoted(?:\s(?:[^<>"']++|"[^"]*+"|'[^']*+')*+)?/?>""")
            .takeUnless { name.any(Char::isWhitespace) }
        private val xmlClose = Regex("""</$quoted\s*>""")

        fun opening(line: String): OpenBlock? = when {
            commentOpen.matches(line) -> OpenBlock(this, Family.COMMENT)
            opensXml(line) -> OpenBlock(this, Family.XML)
            else -> null
        }

        fun opensXml(line: String): Boolean = xmlTag?.matches(line) == true

        fun closes(line: String, family: Family): Boolean = when (family) {
            Family.COMMENT -> commentClose.matches(line)
            Family.XML -> xmlClose.matches(line)
        }
    }
}
