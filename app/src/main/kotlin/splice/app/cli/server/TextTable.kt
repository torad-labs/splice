// NEW: server mode (docs/specs/server-mode.md): the one way `splice server` prints a table for a person reading a
// terminal (keys, ledger rows, budgets): headings over rows, each column as wide as its widest cell.
package splice.app.cli.server

internal class TextTable(private val headings: List<String>) {
    /** [rows] under the headings, two spaces between columns, no trailing blanks. */
    fun of(rows: List<List<String>>): String {
        val all = listOf(headings) + rows
        val widths = headings.indices.map { column -> all.maxOf { it[column].length } }
        return all.joinToString("\n") { row ->
            row.mapIndexed { column, cell -> cell.padEnd(widths[column]) }.joinToString("  ").trimEnd()
        }
    }
}
