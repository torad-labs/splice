// NEW: server mode (docs/specs/server-mode.md, F5, ledger-reports). A usage report in the three shapes it is read in:
// JSON for a script, CSV for a spreadsheet and a markdown table for a page. The columns are named once, here, in the
// order every shape prints them, and the CSV and the table are drawn from the JSON report, so the CLI, which reads the
// JSON from the daemon, draws the very same text the control plane and the listener answer.
package splice.server.ledger

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode

/** The shapes a report is answered in. */
public enum class ReportFormat(public val wire: String, public val contentType: String) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv; charset=utf-8"),
    MD("md", "text/markdown; charset=utf-8"),
}

public object UsageTables {
    // Every column of a group, in the order each shape prints them.
    private val columns = listOf(
        "key", "requests", "errors", "client_gone", "prompt_tokens", "cached_tokens", "completion_tokens",
        "cost_usd", "reference_cost_usd",
    )

    /** One group as the JSON report carries it. */
    internal fun json(group: UsageGroup): JsonObject = buildJsonObject {
        put("key", group.key)
        put("requests", group.requests)
        put("errors", group.errors)
        put("client_gone", group.clientGone)
        put("prompt_tokens", group.tokens.prompt)
        put("cached_tokens", group.tokens.cached)
        put("completion_tokens", group.tokens.completion)
        put("cost_usd", group.costUsd)
        put("reference_cost_usd", group.referenceCostUsd)
    }

    /** [report] in [format]: the JSON itself, or a CSV or markdown table of its groups. */
    public fun text(report: JsonObject, format: ReportFormat): String = when (format) {
        ReportFormat.JSON -> report.toString()
        ReportFormat.CSV -> (listOf(columns) + cells(report)).joinToString("\n") { row -> csvLine(row) }
        ReportFormat.MD -> markdown(report)
    }

    private fun markdown(report: JsonObject): String {
        val head = columns.joinToString(" | ", "| ", " |")
        val rule = columns.joinToString(" | ", "| ", " |") { "---" }
        val rows = cells(report).map { row -> row.joinToString(" | ", "| ", " |") { it.replace("|", "\\|") } }
        return (listOf(head, rule) + rows).joinToString("\n")
    }

    /** Each group's cells, as text: a figure in plain digits, an absent one empty. */
    private fun cells(report: JsonObject): List<List<String>> =
        (report["groups"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { group ->
            columns.map { column -> cell(group[column]) }
        }

    private fun cell(value: JsonElement?): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> if (value.isString) value.content else figure(value.content)
        is JsonObject, is JsonArray -> value.toString()
    }

    /** [usd] in plain digits, to the millionth at most, as every report and refusal writes an amount. */
    public fun amount(usd: Double): String = plain(BigDecimal.valueOf(usd))

    private fun figure(text: String): String = text.toBigDecimalOrNull()?.let(::plain) ?: text

    /** [number] without an exponent, to the millionth of a dollar at most. */
    private fun plain(number: BigDecimal): String =
        number.setScale(FIGURE_PLACES, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun csvLine(row: List<String>): String = row.joinToString(",") { csv(it) }

    /** [cell] as one CSV field, quoted when it holds a comma, a quote or a line break. */
    private fun csv(cell: String): String =
        if (cell.any { it in CSV_QUOTED }) "\"${cell.replace("\"", "\"\"")}\"" else cell
}

// The characters a CSV field is quoted for.
private val CSV_QUOTED = setOf(',', '"', '\n', '\r')

// why: the smallest figure a report carries is a cost; a millionth of a dollar is below any single request's.
private const val FIGURE_PLACES = 6
