// NEW: server mode (docs/specs/server-mode.md, F5): what `splice server usage` and `splice server budget` print for a
// person reading a terminal, from the daemon's answers. `--json` prints those answers whole instead.
package splice.app.cli.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class UsageLines(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val second = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val table =
        TextTable(listOf("AT", "PERSON", "DEVICE", "MODEL", "ROUTE", "STATUS", "OUTCOME", "TOKENS", "MS"))

    /** The window's rows as a table, oldest first; tokens as prompt/cached/completion. */
    fun rows(reply: JsonObject): String {
        val rows = (reply["rows"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (rows.isEmpty()) return "no requests in this window"
        return table.of(
            rows.map { row ->
                listOf(
                    JsonScalars.str(row, "at")?.let(::at) ?: NONE,
                    JsonScalars.str(row, "person").orEmpty(),
                    JsonScalars.str(row, "device").orEmpty(),
                    JsonScalars.str(row, "model") ?: NONE,
                    JsonScalars.str(row, "route").orEmpty(),
                    JsonScalars.long(row, "status")?.toString() ?: NONE,
                    JsonScalars.str(row, "outcome").orEmpty(),
                    listOf("prompt_tokens", "cached_tokens", "completion_tokens")
                        .joinToString("/") { (JsonScalars.long(row, it) ?: 0).toString() },
                    JsonScalars.long(row, "duration_ms")?.toString() ?: NONE,
                )
            },
        )
    }

    /** What an export wrote. */
    fun exported(reply: JsonObject): String {
        val rows = JsonScalars.long(reply, "rows") ?: 0
        val hours = JsonScalars.long(reply, "hours") ?: 0
        return "exported $rows rows in $hours hourly files to ${JsonScalars.str(reply, "to")}"
    }

    /** The daemon's instant, to the second, on this terminal's clock. */
    private fun at(instant: String): String = second.format(Instant.parse(instant).atZone(zone))
}
