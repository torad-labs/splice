// NEW: server mode (docs/specs/server-mode.md): what `splice server` prints for a person reading a terminal, from the
// daemon's answers. `--json` prints those answers whole instead.
package splice.app.cli.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class ServerLines(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val headings = listOf("PREFIX", "PERSON", "NAME", "SOURCE", "CREATED", "LAST USED", "STATE")

    /** A new key: the key alone on the first line, so a script can take it, then whose it is and that it is not
     *  shown again. */
    fun minted(reply: JsonObject): String {
        val named = JsonScalars.str(reply, "name")?.let { " ($it)" }.orEmpty()
        val expires = JsonScalars.str(reply, "expires_at")?.let { ", expires ${at(it)}" }.orEmpty()
        return "${JsonScalars.str(reply, "key")}\n" +
            "for ${JsonScalars.str(reply, "person")}$named$expires. " +
            "This is the one time it is shown: splice keeps only its hash."
    }

    /** The keys as a table, oldest first, as the daemon lists them. */
    fun keys(reply: JsonObject): String {
        val rows = (reply["keys"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (rows.isEmpty()) return "no keys; `splice server key create --person <email>` issues one"
        val table = listOf(headings) + rows.map { row ->
            listOf(
                JsonScalars.str(row, "prefix").orEmpty(),
                JsonScalars.str(row, "person").orEmpty(),
                JsonScalars.str(row, "name") ?: NONE,
                JsonScalars.str(row, "source").orEmpty(),
                JsonScalars.str(row, "created_at")?.let(::at) ?: NONE,
                JsonScalars.str(row, "last_used_at")?.let(::at) ?: "never",
                state(row),
            )
        }
        val widths = headings.indices.map { column -> table.maxOf { it[column].length } }
        return table.joinToString("\n") { row ->
            row.mapIndexed { column, cell -> cell.padEnd(widths[column]) }.joinToString("  ").trimEnd()
        }
    }

    fun revoked(reply: JsonObject): String =
        "revoked ${JsonScalars.str(reply, "prefix")} of ${JsonScalars.str(reply, "person")}"

    fun revokedPerson(reply: JsonObject): String {
        val person = JsonScalars.str(reply, "person")
        return when (val count = JsonScalars.long(reply, "revoked") ?: 0) {
            0L -> "$person holds no live key, so none was revoked"
            1L -> "revoked 1 key of $person"
            else -> "revoked $count keys of $person"
        }
    }

    /** One person's own settings as they now stand. */
    fun person(reply: JsonObject): String {
        val inflight = cap(JsonScalars.long(reply, "inflight"))
        val rpm = cap(JsonScalars.long(reply, "rpm"))
        val upstream = JsonScalars.str(reply, "upstream")?.let { "$it while it is healthy" } ?: "the least busy"
        return "${JsonScalars.str(reply, "person")}: turns in flight $inflight, turns a minute $rpm, upstream $upstream"
    }

    private fun cap(set: Long?): String = when (set) {
        null -> "as [server.limits] sets"
        0L -> "not capped"
        else -> "at most $set"
    }

    /** Whether a key still opens the listener, and when it stopped or will stop. */
    private fun state(row: JsonObject): String {
        val revoked = JsonScalars.str(row, "revoked_at")
        val expires = JsonScalars.str(row, "expires_at")
        return when {
            revoked != null -> "revoked ${at(revoked)}"
            expires == null -> "live"
            Instant.parse(expires).isAfter(Instant.now()) -> "live until ${at(expires)}"
            else -> "expired ${at(expires)}"
        }
    }

    /** The daemon's instant, to the minute, on this terminal's clock. */
    private fun at(instant: String): String = minute.format(Instant.parse(instant).atZone(zone))
}

private const val NONE = "-"
