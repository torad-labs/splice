package splice.app.cli.mod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.app.mods.install.BUN_NOT_FOUND
import splice.core.util.JsonScalars

private const val TURN_NOTICE =
    "It will hear turns from now on, including in sessions already running. " +
        "No session.start is replayed; new sessions announce their start."

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

    fun list(body: JsonObject): String {
        val mods = (body["mods"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return if (mods.isEmpty()) {
            "No mods installed. Use splice mod install <folder|tarball> to add one."
        } else {
            mods.joinToString("\n", transform = ::line)
        }
    }

    fun remove(row: JsonObject): String {
        val after = if (linked(row)) "Its source folder was left alone." else "Its installed copy was removed."
        return "Removed ${name(row)}. It will no longer receive events. $after"
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
        val state = value(row, "state")
        if (state != "disabled") {
            append("; $state")
            val why = value(row, "why")
            if (why.isNotBlank()) append(": $why")
        }
        append("; dropped events: ${value(row, "dropped")}")
    }

    private fun next(row: JsonObject): String {
        val why = value(row, "why")
        return when {
            why == BUN_NOT_FOUND ->
                "$BUN_NOT_FOUND. It will stay failed until Bun is found. " +
                    "Install Bun from https://bun.sh, then run splice mod enable ${name(
                        row,
                    )}. Once it runs: $TURN_NOTICE"
            value(row, "state") == "failed" -> "It is not running: $why. Once it runs: $TURN_NOTICE"
            else -> TURN_NOTICE
        }
    }

    private fun linked(row: JsonObject): Boolean = value(row, "linked") == "true"

    private fun linkNotice(row: JsonObject): String =
        "Linked to ${value(row, "linkedPath")}; not hash-checked. It reads that folder at each start."

    private fun name(row: JsonObject): String = value(row, "name")

    private fun value(row: JsonObject, key: String): String =
        JsonScalars.str(row, key).orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("")
}
