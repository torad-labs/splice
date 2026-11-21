// NEW: gateway-no-heads. The text edit behind the fix session's move: a value taken off its line in one table and put as
// `key = value` in another, which is added at the end of the file when the file has no such table yet. A key added right
// under its table's header is as valid as one added after the table's last, and sits where the person looks first.
package splice.app.cli

internal class TomlMove(private val lines: List<String>) {
    /** [lines] with `key = value` under the header of [table], and line number [removed] (1-based) taken out when it
     *  names one. */
    /** The text after the `=` of the assignment on [line] (1-based), when there is one. */
    fun valueAt(line: Int?): String? = line?.let { lines.getOrNull(it - 1) }
        ?.substringAfter('=', "")?.trim()?.takeIf(String::isNotEmpty)

    fun of(table: String, key: String, value: String, removed: Int?): List<String> {
        val kept = lines.filterIndexed { index, _ -> index + 1 != removed }
        val header = "[$table]"
        val at = kept.indexOfFirst { it.trim() == header }
        val entry = "$key = $value"
        return if (at >= 0) kept.take(at + 1) + entry + kept.drop(at + 1) else kept + listOf("", header, entry)
    }
}
