package splice.app.cli.mod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.app.mods.MOD_RUNTIME_STATE_FIELD
import splice.app.mods.handover.MOD_HANDOVER_DROPPED
import splice.app.mods.install.BUN_NOT_FOUND
import splice.core.util.JsonScalars
import java.nio.file.Path

private const val SESSION_NOTICE = "No session.start is replayed; new sessions announce their start."

/** Operator-facing words for the management answers. No installed code runs to print them. */
internal class ModLines {
    fun install(row: JsonObject): String = buildString {
        val name = name(row)
        appendLine("Installed $name ${value(row, "version")}. ${value(row, "description")}")
        val caps = (row["capabilities"] as? JsonArray).orEmpty().map(JsonScalars::strOrEmpty)
        if (caps.isEmpty()) {
            appendLine("Declared capabilities: none.")
        } else {
            caps.forEach { appendLine("Declared capability: $it") }
        }
        if (linked(row)) {
            appendLine(linkNotice(row))
        } else {
            appendLine("Copied into Splice state and recorded SHA-256 hashes.")
        }
        appendLine("Mods run as you, not in a sandbox. Only TypeScript mods run for now.")
        append(next(row))
    }

    fun list(body: JsonObject): String = buildString {
        val mods = (body["mods"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (mods.isEmpty()) {
            append("No mods installed. Use splice mod install <folder|tarball> to add one.")
        } else {
            append(mods.joinToString("\n", transform = ::line))
        }
        val dropped = value(body, MOD_HANDOVER_DROPPED).toLongOrNull() ?: 0
        if (dropped > 0) append("\nEvents dropped during daemon handover: $dropped.")
    }

    /** [kept] are the store folder and log files still on disk; a reinstall finds the store again. */
    fun remove(row: JsonObject, kept: List<Path>): String = buildString {
        val after = if (linked(row)) "Its source folder was left alone." else "Its installed copy was removed."
        append("Removed ${name(row)}. It will no longer receive events. $after")
        if (kept.isNotEmpty()) {
            appendLine()
            appendLine("Kept on disk: its data store and its log. A reinstall finds the store again.")
            append("To delete them, run: trash ")
            // Single quotes, with an embedded quote written as '\'' so the printed command pastes safely.
            append(kept.joinToString(" ") { "'" + it.toString().replace("'", "'\\''") + "'" })
        }
    }

    fun switch(row: JsonObject, enabled: Boolean, accept: Boolean): String = buildString {
        val name = name(row)
        if (!enabled) {
            append("Disabled $name. It will stop receiving events. To run it again: splice mod enable $name.")
        } else {
            if (accept && !linked(row)) appendLine("Accepted the current files and recorded new SHA-256 hashes.")
            appendLine("Enabled $name.")
            if (linked(row)) appendLine(linkNotice(row))
            append(next(row))
        }
    }

    private fun line(row: JsonObject): String = buildString {
        append("${name(row)} ${value(row, "version")}: ")
        append(if (value(row, "enabled") == "true") "enabled" else "disabled")
        if (linked(row)) append("; ${linkNotice(row)}")
        append(runtime(row))
        value(row, "lastError").takeIf(String::isNotBlank)?.let { append("; last handler error: $it") }
        append(drops(row))
        value(row, "bunVersion").takeIf(String::isNotBlank)?.let { append("; Bun $it") }
        notices(row).forEach { append("\n  $it") }
    }

    private fun drops(row: JsonObject): String = buildString {
        append("; dropped events: ${value(row, "dropped")}")
        for ((key, label) in listOf("droppedTransport" to "pipe lines", "droppedLogLines" to "log lines")) {
            val count = value(row, key).toLongOrNull() ?: 0L
            if (count > 0L) append("; dropped $label: $count")
        }
    }

    private fun runtime(row: JsonObject): String = buildString {
        val state = value(row, MOD_RUNTIME_STATE_FIELD)
        if (state != "disabled") {
            append("; $state")
            value(row, "why").takeIf(String::isNotBlank)?.let { append(": $it") }
        }
    }

    private fun next(row: JsonObject): String {
        val why = value(row, "why")
        val message = when {
            why == BUN_NOT_FOUND ->
                "$BUN_NOT_FOUND. Install Bun from https://bun.sh, then run splice mod enable ${name(row)}.\n" +
                    "Once running, it will hear turns, including in sessions already running. $SESSION_NOTICE"
            value(row, MOD_RUNTIME_STATE_FIELD) == "failed" -> "It is not running: $why"
            value(row, MOD_RUNTIME_STATE_FIELD) == "starting" -> "Still loading. See splice mod list for its state."
            value(row, MOD_RUNTIME_STATE_FIELD) == "restarting" -> why
            else -> "It will hear turns from now on, including in sessions already running. $SESSION_NOTICE"
        }
        return (notices(row) + message).joinToString("\n")
    }

    private fun notices(row: JsonObject): List<String> =
        listOfNotNull(value(row, "notice").takeIf(String::isNotBlank)) +
            listOf("refused", "declaredNotServed").flatMap { key ->
                (row[key] as? JsonArray).orEmpty().map { text(JsonScalars.strOrEmpty(it)) }
            }

    private fun linked(row: JsonObject): Boolean = value(row, "linked") == "true"

    private fun linkNotice(row: JsonObject): String =
        "Linked to ${value(row, "linkedPath")}; not hash-checked. It reads that folder at each start."

    private fun name(row: JsonObject): String = value(row, "name")

    private fun value(row: JsonObject, key: String): String = text(JsonScalars.str(row, key).orEmpty())

    private fun text(value: String): String = value.map { if (it.isISOControl()) ' ' else it }.joinToString("")
}
