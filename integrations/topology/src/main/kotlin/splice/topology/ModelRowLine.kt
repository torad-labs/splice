// NEW: the line a finding about one provider model row sits on (fail-closed boot, v0.4.0 section 10). A row is an
// array-of-tables entry, `[[providers.<p>.models]]` with `id = "<id>"`, which a dotted key path cannot name, so the
// finding names it as `providers.<p>.models[<id>]`, with `.<key>` after it for one setting of that row.
package splice.topology

internal class ModelRowLine {
    private val path = Regex("""^providers\.([^.\[]+)\.models\[(.+)]((?:\.\w+)?)$""")
    private val quoted = Regex("^\"([^\"]*)\"")

    /** The line of [findingPath]'s key inside its row, else the row's header line; null when [findingPath] names no
     *  row or the file holds no such row. */
    fun of(text: String, findingPath: String): Int? {
        val (provider, id, key) = path.matchEntire(findingPath)?.destructured ?: return null
        val header = "[[providers.$provider.models]]"
        var row: Row? = null
        for ((index, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                val done = row?.takeIf { it.id == id }
                if (done != null) return done.lineOf(key)
                row = if (line.replace(" ", "").replace("\"", "") == header) Row(index + 1) else null
            } else {
                row?.read(line, index + 1)
            }
        }
        return row?.takeIf { it.id == id }?.lineOf(key)
    }

    /** One `[[providers.<p>.models]]` entry as it is read: its header line, its id, and the line of each key. */
    private inner class Row(private val header: Int) {
        var id: String? = null
        private val keys = mutableMapOf<String, Int>()

        fun read(line: String, at: Int) {
            if (line.startsWith("#") || !line.contains('=')) return
            val key = line.substringBefore('=').trim()
            keys[key] = at
            if (key == "id") id = quoted.find(line.substringAfter('=').trim())?.groupValues?.get(1)
        }

        fun lineOf(key: String): Int = keys[key.removePrefix(".")] ?: header
    }
}
