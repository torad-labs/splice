// Reads tools/gate/config/ladder.json into typed rows. The gate-ladder plugin read the rows with unchecked casts, so a row of the
// wrong shape failed as a ClassCastException on a line of the script; here every field is checked and a bad row is refused by
// its task name and the field, which is what a person editing the table needs to read.
package splice.ladder

import groovy.json.JsonSlurper
import java.io.File

object LadderTable {
    fun readAll(table: File): List<LadderLeg> {
        val root = JsonSlurper().parse(table) as? Map<*, *> ?: error("${table.path}: not a JSON object")
        val rows = root["legs"] as? List<*> ?: error("${table.path}: no legs array")
        return rows.mapIndexed { index, row ->
            parse(
                row as? Map<*, *> ?: error("${table.path}: legs[$index] is not an object"),
            )
        }
    }

    internal fun parse(row: Map<*, *>): LadderLeg {
        val task = text(row, "task", "a leg")
        return LadderLeg(
            task = task,
            command = texts(row, "command", task) ?: error("$task: no command"),
            why = text(row, "why", task),
            dependsOn = texts(row, "dependsOn", task).orEmpty(),
            afterAllTests = row["afterAllTests"] == true,
            fresh = row["fresh"] == true,
            files = LegFiles(
                inputs = texts(row, "inputs", task).orEmpty(),
                creates = optionalText(row, "creates", task),
                owns = optionalText(row, "owns", task),
            ),
        )
    }

    private fun text(row: Map<*, *>, key: String, owner: String): String =
        row[key] as? String ?: error("$owner: `$key` is missing or not a string")

    private fun optionalText(row: Map<*, *>, key: String, owner: String): String? =
        if (row[key] == null) null else text(row, key, owner)

    private fun texts(row: Map<*, *>, key: String, owner: String): List<String>? {
        val value = row[key] ?: return null
        val list = value as? List<*> ?: error("$owner: `$key` is not a list")
        return list.map { it as? String ?: error("$owner: `$key` holds a non-string entry") }
    }
}
