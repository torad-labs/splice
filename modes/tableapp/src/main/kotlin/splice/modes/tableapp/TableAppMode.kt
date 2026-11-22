// Markdown-table-to-MCP-App sample mode (modes/README.md): validates and normalizes a Markdown pipe table into a
// plain-text fallback for non-MCP-App clients, while MCP-App clients render resources/table.html with the same inputs.
package splice.modes.tableapp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.modes.api.Mode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall

private const val RENDER_TABLE = "render_table"

/** Parses a GitHub-flavored Markdown pipe table and returns a clean plain-text table summary. */
internal class TableAppMode : Mode {
    override fun answer(call: ModeCall): ModeAnswer {
        val markdown = if (call.tool == RENDER_TABLE) string(call.input, "markdown") else null
        val parsed = markdown?.let(::parseTable)
        return when {
            call.tool != RENDER_TABLE -> ModeAnswer.Error("there is no tool ${call.tool} in this mode")
            markdown == null -> ModeAnswer.Error("render_table needs a markdown pipe table, as a string")
            parsed == null -> ModeAnswer.Error(
                "render_table needs a header row, a separator row, and pipe-delimited columns",
            )
            else -> format(string(call.input, "title"), parsed)
        }
    }

    private fun format(rawTitle: String?, parsed: ParsedTable): ModeAnswer {
        val title = rawTitle?.trim()?.takeIf { it.isNotEmpty() }
        val lines = buildList {
            if (title != null) add(title)
            add(parsed.headers.joinToString(" | "))
            add(parsed.headers.joinToString(" | ") { "-".repeat(it.length.coerceAtLeast(MIN_RULE_WIDTH)) })
            parsed.rows.forEach { row -> add(row.joinToString(" | ")) }
        }
        return ModeAnswer.Text(lines.joinToString("\n"))
    }

    private fun parseTable(markdown: String): ParsedTable? {
        val pipeLines = markdown.lines().map { it.trim() }.filter { '|' in it }
        if (pipeLines.size < 2 || !isSeparator(pipeLines[1])) return null
        val headers = splitCells(pipeLines[0])
        if (headers.isEmpty()) return null
        val rows = pipeLines.drop(2).map { line ->
            val cells = splitCells(line)
            List(headers.size) { idx -> cells.getOrElse(idx) { "" } }
        }
        return ParsedTable(headers, rows)
    }

    private fun splitCells(line: String): List<String> =
        line.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun isSeparator(line: String): Boolean =
        '-' in line && line.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }

    private fun string(input: JsonObject, key: String): String? =
        (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private data class ParsedTable(val headers: List<String>, val rows: List<List<String>>)
}

// why: minimum dash count in the fallback Markdown separator row so short column headers stay valid GFM.
private const val MIN_RULE_WIDTH = 3
