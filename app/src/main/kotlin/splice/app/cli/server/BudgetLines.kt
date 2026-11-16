// NEW: server mode (docs/specs/server-mode.md, F5, person-budgets): what `splice server budget` prints for a person
// reading a terminal, from the daemon's answers; `--json` prints those answers whole instead.
package splice.app.cli.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.server.ledger.UsageTables

internal class BudgetLines {
    /** Every budget as a table, amounts in USD. */
    fun budgets(reply: JsonObject): String {
        val rows = (reply["budgets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (rows.isEmpty()) return "no budgets; `splice server budget set --person <email> --daily-usd N` sets one"
        return TextTable(COLUMNS.keys.toList()).of(rows.map { row -> COLUMNS.values.map { cell(row, it) } })
    }

    /** The budget just set, in a line. */
    fun budget(reply: JsonObject): String {
        val of = { heading: String -> cell(reply, COLUMNS.getValue(heading)) }
        return "${of("SCOPE")} ${of("NAME")}: daily ${of("DAILY")}, monthly ${of("MONTHLY")} USD, " +
            "${of("ACTION")} when reached; spent today ${of("TODAY")}, this month ${of("MONTH")} USD (${of("STATE")})"
    }

    /** [field] of [row] as a cell: an amount in USD without an exponent, any other value as it is. */
    private fun cell(row: JsonObject, field: String): String {
        val text = JsonScalars.str(row, field) ?: return NONE
        return if (field in AMOUNTS) text.toDoubleOrNull()?.let(UsageTables::amount) ?: text else text
    }
}

// A budget's fields as `budget list --json` names them, by the table's heading, in the table's order.
private val COLUMNS = linkedMapOf(
    "SCOPE" to "scope",
    "NAME" to "name",
    "DAILY" to "daily_usd",
    "MONTHLY" to "monthly_usd",
    "ACTION" to "action",
    "TODAY" to "spent_today_usd",
    "MONTH" to "spent_month_usd",
    "STATE" to "state",
)

// The fields that are amounts in USD.
private val AMOUNTS = setOf("daily_usd", "monthly_usd", "spent_today_usd", "spent_month_usd")
